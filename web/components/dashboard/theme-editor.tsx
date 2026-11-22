"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "@/lib/client";
import { checkContrast, contrast, deriveFromBrand, isHex, readableOn, sanitizeTokens, THEME_TOKENS, type ThemeToken, type ThemeTokens } from "@/lib/theme";
import { encodeThemeParam } from "@/lib/theme-param";
import { CURATED_PALETTES, defaultTokens } from "@/lib/template-palettes";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Segmented } from "../ui";

type Profile = { template?: string; siteTheme?: { tokens: ThemeTokens } | null };
type Branding = { primaryColor?: string; logoFileId?: string };
type Device = "desktop" | "mobile";

const PROFILE_PATH = { STORE: "store/profile", SERVICE: "store/profile", CLINIC: "clinic/profile", LAWYER: "lawyer/profile" } as const;
const HARD_FLOOR = 3;   // the backend rejects below this; the editor warns earlier (4.5:1 for copy and buttons)

/** A tiny rendition of a palette: page, card, heading, body line and a button. Shown for every suggestion so the merchant sees it before applying. */
function MiniPreview({ tokens, label }: { tokens: ThemeTokens; label?: string }) {
  const m = tokens;
  return (
    <span className="block overflow-hidden rounded-[10px] border border-line" style={{ background: m.bg, borderColor: m.border }} aria-label={label}>
      <span className="block space-y-1.5 p-2.5">
        <span className="block h-2 w-1/2 rounded-full" style={{ background: m.text }} />
        <span className="block h-1.5 w-3/4 rounded-full" style={{ background: m.text2, opacity: 0.7 }} />
        <span className="flex items-center gap-1.5 pt-1">
          <span className="block h-4 w-12 rounded-md" style={{ background: m.btnBg }}><span className="mx-auto mt-[5px] block h-1 w-7 rounded-full" style={{ background: m.btnText }} /></span>
          <span className="block h-4 flex-1 rounded-md" style={{ background: m.surface, boxShadow: `inset 0 0 0 1px ${m.border}` }} />
        </span>
        <span className="flex gap-1 pt-0.5">{(["primary", "secondary", "accent", "bg2"] as ThemeToken[]).map((k) => <i key={k} className="block h-2.5 w-2.5 rounded-full" style={{ background: m[k] }} />)}</span>
      </span>
    </span>
  );
}

/** Dominant brand colours of the logo, read in the browser (the picture never leaves it). Near-white, near-black and grey are skipped. */
async function logoColors(src: string): Promise<string[]> {
  const img = new Image();
  img.crossOrigin = "anonymous";
  await new Promise<void>((ok, bad) => { img.onload = () => ok(); img.onerror = () => bad(new Error("logo")); img.src = src; });
  const S = 64;
  const c = document.createElement("canvas");
  c.width = S; c.height = S;
  const ctx = c.getContext("2d", { willReadFrequently: true });
  if (!ctx) return [];
  ctx.drawImage(img, 0, 0, S, S);
  const px = ctx.getImageData(0, 0, S, S).data;
  const buckets = new Map<number, { n: number; r: number; g: number; b: number }>();
  for (let i = 0; i < px.length; i += 4) {
    const [r, g, b, a] = [px[i], px[i + 1], px[i + 2], px[i + 3]];
    if (a < 160) continue;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 510, sat = mx === mn ? 0 : (mx - mn) / (255 - Math.abs(mx + mn - 255));
    if (l > 0.93 || l < 0.08 || sat < 0.2) continue;
    const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const e = buckets.get(key) ?? { n: 0, r: 0, g: 0, b: 0 };
    e.n += 1; e.r += r; e.g += g; e.b += b;
    buckets.set(key, e);
  }
  const hex = (e: { n: number; r: number; g: number; b: number }) => "#" + [e.r, e.g, e.b].map((v) => Math.round(v / e.n).toString(16).padStart(2, "0")).join("");
  const dist = (a: string, b: string) => Math.hypot(parseInt(a.slice(1, 3), 16) - parseInt(b.slice(1, 3), 16), parseInt(a.slice(3, 5), 16) - parseInt(b.slice(3, 5), 16), parseInt(a.slice(5, 7), 16) - parseInt(b.slice(5, 7), 16));
  const out: string[] = [];
  for (const e of [...buckets.values()].sort((x, y) => y.n - x.n)) {
    const h = hex(e);
    if (out.every((o) => dist(o, h) > 70)) out.push(h);
    if (out.length === 3) break;
  }
  return out;
}

/**
 * The colour editor of a website (shop, service business, clinic or lawyer). Simple mode derives a whole palette from one or two brand colours;
 * Advanced edits every token. Suggestions (from the logo, curated, the design's own) are previews that apply only on an explicit click. The preview
 * is the real site in an iframe (same server path as the published site, with the draft passed as ?theme=). Save publishes at once: there is no
 * separate draft stored on the server, the unsaved state lives in this editor (with undo and redo) until you save.
 */
export default function ThemeEditor() {
  const { t, tenantType, locale } = useI18n();
  const path = PROFILE_PATH[tenantType ?? "STORE"];
  const profile = useApi<Profile>(path);
  const branding = useApi<Branding>("tenant/branding");
  const saved = useMemo<ThemeTokens>(() => sanitizeTokens(profile.data?.siteTheme?.tokens), [profile.data]);
  const base = useMemo(() => defaultTokens(profile.data?.template) ?? deriveFromBrand(branding.data?.primaryColor ?? "#0f766e"), [profile.data?.template, branding.data?.primaryColor]);

  // draft with undo / redo (session only)
  const [hist, setHist] = useState<{ past: ThemeTokens[]; now: ThemeTokens | null; future: ThemeTokens[] }>({ past: [], now: null, future: [] });
  const draft = hist.now ?? saved;
  const commit = useCallback((next: ThemeTokens) => setHist((h) => ({ past: [...h.past, h.now ?? saved].slice(-50), now: next, future: [] })), [saved]);
  const undo = () => setHist((h) => h.past.length ? { past: h.past.slice(0, -1), now: h.past[h.past.length - 1], future: [h.now ?? saved, ...h.future] } : h);
  const redo = () => setHist((h) => h.future.length ? { past: [...h.past, h.now ?? saved], now: h.future[0], future: h.future.slice(1) } : h);
  const dirty = JSON.stringify(draft) !== JSON.stringify(saved);

  const eff: ThemeTokens = { ...base, ...draft };
  const warnings = checkContrast(draft, base);
  const blocking = warnings.filter((w) => w.ratio < HARD_FLOOR && (w.pair[0] === "text" || w.pair[0] === "btnText") && (w.pair[1] === "bg" || w.pair[1] === "btnBg"));

  const [mode, setMode] = useState<"simple" | "advanced">("simple");
  const [dark, setDark] = useState(false);
  const [device, setDevice] = useState<Device>("desktop");
  const [lang, setLang] = useState<"ar" | "en">(locale === "en" ? "en" : "ar");
  const [done, setDone] = useState(false);

  const save = useAction(async () => {
    const tokens = sanitizeTokens(draft);
    await api(path, { method: "PATCH", body: { siteTheme: Object.keys(tokens).length ? { tokens } : null } });
    await profile.reload();
    setHist({ past: [], now: null, future: [] });
    setDone(true);
  });

  // palette suggestions from the logo (in the browser)
  const [logoPal, setLogoPal] = useState<ThemeTokens[] | null>(null);
  const [logoState, setLogoState] = useState<"idle" | "busy" | "none" | "fail">("idle");
  const logoId = branding.data?.logoFileId;
  const readLogo = async () => {
    if (!logoId) return;
    setLogoState("busy");
    try {
      const cols = await logoColors(`/api/bff/files/${logoId}/content?variant=thumb`);
      if (!cols.length) { setLogoState("none"); setLogoPal([]); return; }
      const [a, b] = cols;
      const list: ThemeTokens[] = [deriveFromBrand(a), deriveFromBrand(a, { dark: true })];
      if (b) list.push(deriveFromBrand(a, { secondary: b }), deriveFromBrand(b));
      setLogoPal(list); setLogoState("idle");
    } catch { setLogoState("fail"); }
  };

  // live preview of the actual site: draft goes in ?theme= (preview frame only), debounced
  const frameSrc = useMemo(() => {
    const th = encodeThemeParam(sanitizeTokens(draft));
    return `/?preview=1&lang=${lang}&theme=${th || "off"}`;
  }, [draft, lang]);
  const [src, setSrc] = useState(frameSrc);
  useEffect(() => { const h = setTimeout(() => setSrc(frameSrc), 350); return () => clearTimeout(h); }, [frameSrc]);
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(0);
  useEffect(() => { const el = box.current; if (!el) return; const ro = new ResizeObserver(() => setW(el.clientWidth)); ro.observe(el); return () => ro.disconnect(); }, [profile.data]);
  const FW = device === "desktop" ? 1280 : 390, FH = device === "desktop" ? 860 : 780;
  const scale = device === "desktop" ? (w ? w / FW : 0) : w ? 1 : 0;

  if (!profile.data) return null;
  const own = defaultTokens(profile.data.template);
  const setTok = (k: ThemeToken, v: string) => commit({ ...draft, [k]: v.toLowerCase() });
  const apply = (p: ThemeTokens) => { setDone(false); commit(sanitizeTokens(p)); };
  const fix = (a: ThemeToken, b: ThemeToken) => { const bg = eff[b]; if (bg && isHex(bg)) setTok(a, readableOn(bg)); };

  const simplePrimary = eff.primary ?? "#0f766e";
  const pairLabel = (a: ThemeToken, b: ThemeToken) => `${t(`te.tok.${a}`)} / ${t(`te.tok.${b}`)}`;

  return (
    <Card className="space-y-5" >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-medium">{t("te.title")}</h2>
          <p className="mt-1 max-w-xl text-sm text-muted">{t("te.hint")}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={undo} disabled={!hist.past.length} aria-label={t("te.undo")}>↶ {t("te.undo")}</Button>
          <Button size="sm" variant="secondary" onClick={redo} disabled={!hist.future.length} aria-label={t("te.redo")}>↷ {t("te.redo")}</Button>
        </div>
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,420px)_minmax(0,1fr)]">
        <div className="space-y-5">
          <Segmented value={mode} onChange={setMode} tabs={[{ key: "simple", label: t("te.simple") }, { key: "advanced", label: t("te.advanced") }]} />

          {mode === "simple" ? (
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label={t("te.tok.primary")}><div className="flex gap-2"><input type="color" aria-label={t("te.tok.primary")} value={simplePrimary} onChange={(e) => apply(deriveFromBrand(e.target.value, { secondary: draft.secondary, dark }))} className="h-10 w-12 rounded border" /><Input value={simplePrimary} dir="ltr" onChange={(e) => isHex(e.target.value) && apply(deriveFromBrand(e.target.value, { secondary: draft.secondary, dark }))} /></div></Field>
                <Field label={t("te.tok.secondary")}><div className="flex gap-2"><input type="color" aria-label={t("te.tok.secondary")} value={eff.secondary ?? "#134e4a"} onChange={(e) => apply(deriveFromBrand(simplePrimary, { secondary: e.target.value, dark }))} className="h-10 w-12 rounded border" /><Input value={eff.secondary ?? ""} dir="ltr" onChange={(e) => isHex(e.target.value) && apply(deriveFromBrand(simplePrimary, { secondary: e.target.value, dark }))} /></div></Field>
              </div>
              <Segmented value={dark ? "dark" : "light"} onChange={(v) => { setDark(v === "dark"); apply(deriveFromBrand(simplePrimary, { secondary: draft.secondary, dark: v === "dark" })); }} tabs={[{ key: "light", label: t("te.light") }, { key: "dark", label: t("te.dark") }]} />
              <p className="text-xs text-muted">{t("te.simpleNote")}</p>
            </div>
          ) : (
            <div className="grid gap-2.5 sm:grid-cols-2">
              {THEME_TOKENS.map((k) => (
                <Field key={k} label={t(`te.tok.${k}`)}>
                  <div className="flex gap-2"><input type="color" aria-label={t(`te.tok.${k}`)} value={eff[k] ?? "#000000"} onChange={(e) => setTok(k, e.target.value)} className="h-10 w-12 shrink-0 rounded border" /><Input value={eff[k] ?? ""} dir="ltr" onChange={(e) => isHex(e.target.value) && setTok(k, e.target.value)} /></div>
                </Field>
              ))}
            </div>
          )}

          {warnings.length > 0 && (
            <div className="space-y-2" role="status">
              {warnings.map((w) => (
                <Alert key={w.pair.join("-")} tone={w.ratio < HARD_FLOOR ? "red" : "amber"}>
                  <span className="flex flex-wrap items-center justify-between gap-2"><span>{t("te.lowContrast", { pair: pairLabel(w.pair[0], w.pair[1]), ratio: String(w.ratio), min: String(w.min) })}</span>
                    <button type="button" className="font-semibold underline" onClick={() => fix(w.pair[0], w.pair[1])}>{t("te.fix")}</button></span>
                </Alert>
              ))}
            </div>
          )}

          <section className="space-y-2">
            <h3 className="text-[13px] font-semibold text-ink-2">{t("te.fromLogo")}</h3>
            {!logoId ? <p className="text-xs text-muted">{t("te.noLogo")}</p> : (
              <>
                <Button size="sm" variant="secondary" loading={logoState === "busy"} onClick={() => void readLogo()}>{t("te.readLogo")}</Button>
                {logoState === "fail" && <p className="text-xs text-red-600">{t("te.logoFail")}</p>}
                {logoState === "none" && <p className="text-xs text-muted">{t("te.logoNone")}</p>}
                {logoPal && logoPal.length > 0 && <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">{logoPal.map((p, i) => <button key={i} type="button" onClick={() => apply(p)} className="text-start transition hover:scale-[1.02]" aria-label={t("te.applyPalette")}><MiniPreview tokens={p} /><span className="mt-1 block text-[11px] text-muted">{t("te.applyPalette")}</span></button>)}</div>}
              </>
            )}
          </section>

          <section className="space-y-2">
            <h3 className="text-[13px] font-semibold text-ink-2">{t("te.curated")}</h3>
            <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
              {own && <button type="button" onClick={() => apply(own)} className="text-start transition hover:scale-[1.02]"><MiniPreview tokens={own} /><span className="mt-1 block text-[11px] font-semibold text-ink">{t("te.original")}</span></button>}
              {CURATED_PALETTES.map((c) => <button key={c.id} type="button" onClick={() => apply(c.tokens)} className="text-start transition hover:scale-[1.02]"><MiniPreview tokens={c.tokens} /><span className="mt-1 block text-[11px] font-semibold text-ink">{t(`te.pal.${c.id}`)}</span></button>)}
            </div>
          </section>

          <ErrorText error={save.error} />
          {done && !dirty && <Alert tone="green">{t("te.published")}</Alert>}
          {blocking.length > 0 && <Alert tone="red">{t("te.blocked")}</Alert>}
          <div className="flex flex-wrap items-center gap-2">
            <Button loading={save.loading} disabled={!dirty || blocking.length > 0} onClick={() => { setDone(false); void save.run(); }}>{t("te.save")}</Button>
            <Button variant="secondary" disabled={!Object.keys(draft).length} onClick={() => apply({})}>{t("te.reset")}</Button>
            {dirty && <span className="text-xs font-medium text-amber-600">{t("te.unsaved")}</span>}
          </div>
          <p className="text-xs text-muted">{t("te.saveNote")}</p>
        </div>

        <div className="min-w-0 space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <Segmented size="sm" value={device} onChange={setDevice} tabs={[{ key: "desktop", label: t("gal.desktop") }, { key: "mobile", label: t("gal.phone") }]} />
            <Segmented size="sm" value={lang} onChange={setLang} tabs={[{ key: "ar", label: "AR" }, { key: "en", label: "EN" }]} />
            <span className="text-xs text-muted">{t("te.previewNote")}</span>
          </div>
          <div ref={box} className={`relative overflow-hidden rounded-[14px] border border-line bg-surface-2 ${device === "mobile" ? "flex justify-center" : ""}`} style={{ height: scale ? FH * scale : 200 }}>
            {scale > 0 && device === "desktop" && (
              <iframe title={t("te.title")} src={src} data-testid="theme-preview" className="absolute start-0 top-0 block origin-top-left border-0 bg-white rtl:origin-top-right" style={{ width: FW, height: FH, transform: `scale(${scale})` }} />
            )}
            {scale > 0 && device === "mobile" && (
              <iframe title={t("te.title")} src={src} data-testid="theme-preview" className="block border-0 bg-white" style={{ width: Math.min(FW, w), height: FH }} />
            )}
          </div>
          <p className="text-[11px] text-muted" dir="ltr">{contrast(eff.text ?? "#000000", eff.bg ?? "#ffffff").toFixed(1)}:1 text / bg &middot; {contrast(eff.btnText ?? "#ffffff", eff.btnBg ?? "#000000").toFixed(1)}:1 button</p>
        </div>
      </div>
    </Card>
  );
}
