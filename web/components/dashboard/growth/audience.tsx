"use client";

import Link from "next/link";
import { ChannelIcon } from "../../channel-icons";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { PanelHead } from "../marketing/kit";
import { PLATFORMS, PLAT_COLOR, SEG_ICON, SEG_KEYS, WA_OWN_LINK, type ByPlatform, type ManualInfo } from "./types";

function toggle(list: string[], k: string) { return list.includes(k) ? list.filter((x) => x !== k) : [...list, k]; }

/** Step 1: which customer groups, and on which platforms. Both are multi-select. */
export default function AudiencePicker({ counts, byPlatform, ownLinked, segs, onSegs, plats, onPlats, manualInfo, manual, onManual }: {
  counts: Record<string, number> | null; byPlatform: ByPlatform | null; ownLinked: boolean;
  segs: string[]; onSegs: (v: string[]) => void; plats: string[]; onPlats: (v: string[]) => void;
  manualInfo: Record<string, ManualInfo> | null; manual: string[]; onManual: (v: string[]) => void;
}) {
  const { t, locale } = useI18n();
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  return (
    <div className="space-y-5">
      <div className="space-y-2.5">
        <PanelHead icon="users" title={t("gx.pickSegments")} hint={t("gx.pickSegmentsHint")} />
        <div role="group" aria-label={t("gx.pickSegments")} className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {SEG_KEYS.map((k) => {
            const on = segs.includes(k);
            return (
              <button key={k} type="button" role="checkbox" aria-checked={on} onClick={() => onSegs(toggle(segs, k))}
                className={`flex items-center gap-2.5 rounded-[14px] p-3 text-start ring-1 transition ${on ? "bg-ink text-surface ring-ink" : "bg-surface ring-line hover:ring-line-strong"}`}>
                <Icon name={on ? "check" : SEG_ICON[k]} className={`h-4 w-4 shrink-0 ${on ? "" : "text-muted"}`} />
                <span className="min-w-0">
                  <span className={`block truncate text-[12px] ${on ? "opacity-80" : "text-muted"}`}>{t(`gr.seg.${k}`)}</span>
                  <span className="block font-display text-[18px] font-semibold leading-none tnum">{counts ? n(counts[k] ?? 0) : "-"}</span>
                </span>
              </button>
            );
          })}
        </div>
        {segs.length === 1 && <p className="text-[12px] text-muted">{t(`gr.segHint.${segs[0]}`)}</p>}
      </div>

      <div className="space-y-2.5">
        <PanelHead icon="chat" title={t("gx.pickPlatforms")} hint={t("gx.pickPlatformsHint")} />
        <div role="group" aria-label={t("gx.pickPlatforms")} className="grid gap-2 sm:grid-cols-3">
          {PLATFORMS.map((p) => {
            const on = plats.includes(p);
            const c = byPlatform?.[p];
            return (
              <div key={p} className={`rounded-[14px] p-3 ring-1 transition ${on ? "bg-surface-2 ring-2" : "bg-surface ring-line hover:ring-line-strong"}`} style={on ? { ["--tw-ring-color" as string]: PLAT_COLOR[p] } : undefined}>
                <button type="button" role="checkbox" aria-checked={on} onClick={() => onPlats(toggle(plats, p))} className="flex w-full items-center gap-2.5 text-start">
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[10px] bg-surface ring-1 ring-line"><ChannelIcon channel={p} className="h-5 w-5" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13.5px] font-semibold text-ink">{t(`gx.plat.${p}`)}</span>
                    <span className="block text-[12px] text-muted tnum">{c ? t("gx.platCount", { reachable: n(c.reachable), total: n(c.total) }) : "-"}</span>
                  </span>
                  <span className={`grid h-[18px] w-[18px] shrink-0 place-items-center rounded-[5px] ring-2 ${on ? "bg-ink text-surface ring-ink" : "ring-line-strong"}`} aria-hidden>{on && <Icon name="check" className="h-3 w-3" />}</span>
                </button>
                {p !== "WHATSAPP" && on && manualInfo?.[p]?.available && (
                  <button type="button" role="checkbox" aria-checked={manual.includes(p)} onClick={() => onManual(toggle(manual, p))}
                    className={`mt-2.5 flex w-full items-start gap-2 rounded-[10px] px-2.5 py-2 text-start text-[12px] ring-1 ring-inset transition ${manual.includes(p) ? "bg-amber-100 text-amber-950 ring-amber-600/40" : "bg-amber-50 text-amber-900 ring-amber-600/20 hover:bg-amber-100"}`}>
                    <span className={`mt-0.5 grid h-[16px] w-[16px] shrink-0 place-items-center rounded-[4px] ring-2 ${manual.includes(p) ? "bg-ink text-surface ring-ink" : "ring-amber-700/50"}`} aria-hidden>{manual.includes(p) && <Icon name="check" className="h-3 w-3" />}</span>
                    <span className="min-w-0">
                      <span className="block font-semibold">{t("mx.third")}{c?.manualStyleReachable ? ` · ${t("mx.thirdCount", { n: n(c.manualStyleReachable) })}` : ""}</span>
                      <span className="block opacity-80">{t("mx.thirdHint")}</span>
                    </span>
                  </button>
                )}
                {p === "WHATSAPP" && !ownLinked && (
                  <Link href={WA_OWN_LINK} className="mt-2.5 flex items-center gap-1.5 rounded-[10px] bg-amber-50 px-2.5 py-2 text-[12px] font-semibold text-amber-900 ring-1 ring-inset ring-amber-600/20 hover:bg-amber-100">
                    <Icon name="link" className="h-3.5 w-3.5 shrink-0" />{t("gx.connectNumber")}<Icon name="arrow" className="ms-auto h-3.5 w-3.5 flip-rtl" />
                  </Link>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
