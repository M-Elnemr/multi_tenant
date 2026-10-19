"use client";

import { useEffect, useRef, useState } from "react";
import { api, newKey } from "@/lib/client";
import { PhoneInput } from "@/components/inputs";
import { useAction, useApi } from "@/components/hooks";
import { CategoryPicker, categoriesValid, type CategoryOption } from "@/components/category-picker";
import { useT } from "@/components/i18n-provider";
import { Button, ErrorText, Field, Input } from "@/components/ui";
import { Icon } from "@/components/icons";
import { LogoMark } from "@/components/brand";
import { AppBenefits } from "@/components/app-benefits";
import { trackStep } from "@/components/visit-tracker";

type Type = "STORE" | "CLINIC";

function slugify(s: string) {
  return s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40);
}

/** A mobile number is required to register: Egyptian 01x xxxxxxxx, or an international +number. */
const validMobile = (p: string) => /^(\+\d{8,15}|01[0125]\d{8}|1[0125]\d{8})$/.test(p.trim());

/** Create a store or a clinic in one screen. The site is live on its own subdomain the moment this finishes. */
export function RegisterWizard({ rootDomain, initialType }: { rootDomain: string; initialType: Type | null }) {
  const t = useT();
  const [type, setType] = useState<Type | null>(initialType);
  const [name, setName] = useState("");
  const [manualSlug, setManualSlug] = useState<string | null>(null);
  // The address is made for the owner: from the name when it has latin letters, else a short random one (an Arabic name has none).
  const [rand] = useState(() => Math.random().toString(36).slice(2, 6));
  const [suffix, setSuffix] = useState("");
  const base = slugify(name);
  const auto = (base.length >= 3 ? base : `${type === "CLINIC" ? "clinic" : "shop"}-${rand}`) + (suffix ? `-${suffix}` : "");
  const slug = manualSlug ?? auto;
  const [editAddr, setEditAddr] = useState(false);
  const [check, setCheck] = useState<{ slug: string; state: "ok" | "taken" | "invalid" }>({ slug: "", state: "ok" });
  const [owner, setOwner] = useState({ name: "", phone: "", password: "" });
  const [showPw, setShowPw] = useState(false);
  const [cats, setCats] = useState<string[]>([]);
  const [otherCat, setOtherCat] = useState("");
  const catOptions = useApi<CategoryOption[]>(type ? `onboarding/categories?type=${type}` : null);
  const [key] = useState(newKey);
  const [done, setDone] = useState<{ host: string; tenantId: string } | null>(null);
  const [opening, setOpening] = useState(false);
  const [ready, setReady] = useState(false);

  // Debounced live availability check; state is only written after the response arrives.
  useEffect(() => {
    if (slug.length < 3) return;
    const h = setTimeout(async () => {
      try {
        const r = await api<{ available: boolean }>(`onboarding/slug-available?slug=${encodeURIComponent(slug)}`);
        if (!r.available && manualSlug === null) setSuffix(Math.random().toString(36).slice(2, 5));   // taken: quietly try another
        setCheck({ slug, state: r.available ? "ok" : "taken" });
      } catch { setCheck({ slug, state: "invalid" }); }
    }, 350);
    return () => clearTimeout(h);
  }, [slug, manualSlug]);
  const avail: "idle" | "checking" | "ok" | "taken" | "invalid" = slug.length < 3 ? "idle" : check.slug === slug ? check.state : "checking";

  // A brand-new address may need up to a minute before its secure certificate exists. Wait for it here, so the button never lands on a browser warning.
  const doneHost = done?.host;
  useEffect(() => {
    if (!doneHost) return;
    let stop = false;
    const probe = async () => {
      try {
        const port = window.location.port ? `:${window.location.port}` : "";
        await fetch(`${window.location.protocol}//${doneHost}${port}/api/v1/tenant/resolve`, { mode: "no-cors", cache: "no-store" });
        if (!stop) setReady(true);
      } catch {
        if (!stop) setTimeout(() => void probe(), 3000);
      }
    };
    void probe();
    return () => { stop = true; };
  }, [doneHost]);

  const siteUrl = (host: string) => {
    const port = window.location.port ? `:${window.location.port}` : "";
    return `${window.location.protocol}//${host}${port}`;
  };

  const create = useAction(async () => {
    const r = await api<{ host: string; tenant: { id: string } }>("onboarding/tenants", {
      body: { type, name: name.trim(), slug, ownerFirstName: name.trim(), phone: owner.phone.trim(), password: owner.password, categories: cats, otherCategory: cats.includes("other") ? otherCat.trim() : undefined },
      idempotencyKey: key,
    });
    trackStep("/register/~done");
    setDone({ host: r.host, tenantId: r.tenant.id });   // the session stays: "open dashboard" signs in on the new site without asking again
  });

  const openDashboard = async () => {
    if (!done) return;
    setOpening(true);
    try {
      const r = await api<{ ticket: string }>("auth/handoff", { body: { tenantId: done.tenantId } });
      window.location.assign(`${siteUrl(done.host)}/api/sso?ticket=${encodeURIComponent(r.ticket)}&next=${encodeURIComponent("/dashboard")}`);
    } catch {
      window.location.assign(`${siteUrl(done.host)}/login?phone=${encodeURIComponent(owner.phone)}`);   // fall back to the normal login
    }
  };

  // As soon as the secure address answers, go straight in: nobody has to find and press a button.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (done && ready && !autoOpened.current) { autoOpened.current = true; void openDashboard(); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [done, ready]);

  useEffect(() => { if (create.error) trackStep("/register/~error"); }, [create.error]);
  useEffect(() => { if (type) trackStep(`/register/~form-${type.toLowerCase()}`); }, [type]);
  const touched = useRef(false);
  const onStart = () => { if (!touched.current) { touched.current = true; trackStep("/register/~typing"); } };

  if (done) {
    const base = siteUrl(done.host);
    return (
      <div className="mx-auto max-w-lg animate-fade-up px-4 py-14 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 animate-pop items-center justify-center rounded-full bg-emerald-100 text-emerald-700 ring-8 ring-emerald-50"><Icon name="check" className="h-8 w-8" /></div>
        <h1 className="text-2xl font-bold">{t("register.liveTitle")}</h1>
        <p className="mt-2 text-slate-600">{t("register.liveBody")}</p>
        <p className="my-4 rounded-xl border border-slate-200 bg-white p-3 font-mono text-sm shadow-soft" dir="ltr">{done.host}</p>
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-center">
          <button type="button" onClick={() => void openDashboard()} disabled={opening || !ready} className="rounded-xl bg-brand-gradient px-5 py-3 text-sm font-semibold text-white shadow-brand transition active:scale-[.97] disabled:opacity-60">{opening ? t("myplaces.opening") : !ready ? t("register.preparing") : t("register.openDashboard")}</button>
          {ready ? <a href={base} className="rounded-lg border border-slate-300 bg-white px-5 py-3 text-sm font-medium">{t("register.viewSite")}</a> : <span className="rounded-lg border border-slate-200 bg-slate-50 px-5 py-3 text-sm text-slate-400">{t("register.viewSite")}</span>}
        </div>
        <p className="mt-6 text-sm text-slate-500">{t("register.domainTip")}</p>
      </div>
    );
  }

  if (!type) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-14">
        <LogoMark className="mx-auto mb-4 h-16 w-16 animate-float" />
        <h1 className="mb-8 animate-fade-up text-center text-2xl font-bold sm:text-3xl">{t("register.chooseType")}</h1>
        <div className="stagger grid gap-4 sm:grid-cols-2">
          {([["STORE", "type.store", "register.storeDesc", "bag"], ["CLINIC", "type.clinic", "register.clinicDesc", "stethoscope"]] as const).map(([v, title, desc, icon], i) => (
            <button key={v} style={{ "--i": i } as React.CSSProperties} onClick={() => setType(v)} className="hover-lift group rounded-3xl border-2 border-slate-200 bg-white p-6 text-start hover:border-brand">
              <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-soft text-brand transition group-hover:bg-brand-gradient group-hover:text-white"><Icon name={icon} className="h-7 w-7" /></span>
              <p className="text-lg font-bold">{t(title)}</p>
              <p className="mt-1 text-sm text-slate-500">{t(desc)}</p>
            </button>
          ))}
        </div>
      </div>
    );
  }

  const valid = name.trim().length >= 2 && avail === "ok" && validMobile(owner.phone) && owner.password.length >= 8 && (cats.length === 0 || categoriesValid(cats, otherCat));
  return (
    <div className="mx-auto max-w-xl animate-fade-up px-4 py-12">
      <button onClick={() => { setType(null); setCats([]); setOtherCat(""); }} className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 transition hover:text-brand"><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" />{t("common.back")}</button>
      <h1 className="mb-1 text-2xl font-bold">{type === "STORE" ? t("register.storeTitle") : t("register.clinicTitle")}</h1>
      <p className="mb-4 text-sm text-slate-500">{t("register.freeTrial")}</p>
      <div className="mb-6"><AppBenefits kind={type} compact /></div>
      <form onSubmit={(e) => { e.preventDefault(); if (valid) { trackStep("/register/~submit"); void create.run(); } }} onFocus={onStart} className="space-y-5 rounded-3xl border border-slate-200/80 bg-white p-6 shadow-lift sm:p-8">
        <Field label={type === "STORE" ? t("register.storeName") : t("register.clinicName")}>
          <Input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} autoComplete="organization" autoFocus />
        </Field>
        <Field label={t("register.phone")} hint={t("register.phoneHint")} error={owner.phone && !validMobile(owner.phone) ? t("register.phoneInvalid") : undefined}><PhoneInput value={owner.phone} onValue={(phone) => setOwner({ ...owner, phone })} required /></Field>
        <Field label={t("login.password")} hint={t("login.passwordRule")}>
          <div className="relative">
            <Input type={showPw ? "text" : "password"} value={owner.password} onChange={(e) => setOwner({ ...owner, password: e.target.value })} required minLength={8} autoComplete="new-password" />
            <button type="button" onClick={() => setShowPw(!showPw)} className="absolute end-3 top-1/2 -translate-y-1/2 text-xs font-medium text-brand">{showPw ? t("register.hidePw") : t("register.showPw")}</button>
          </div>
        </Field>
        <div className="rounded-xl bg-slate-50 p-3 text-sm" dir="ltr">
          {!editAddr ? (
            <div className="flex items-center justify-between gap-2">
              <span className="min-w-0 truncate font-mono text-slate-700">{slug}.{rootDomain}</span>
              <button type="button" onClick={() => { setEditAddr(true); setManualSlug(slug); }} className="shrink-0 text-xs font-medium text-brand">{t("register.editAddress")}</button>
            </div>
          ) : (
            <div>
              <div className="flex items-center gap-2">
                <Input value={slug} onChange={(e) => setManualSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))} required minLength={3} maxLength={40} className="text-end" />
                <span className="whitespace-nowrap text-slate-500">.{rootDomain}</span>
              </div>
              {avail === "checking" && <p className="mt-1 text-xs text-slate-500">{t("register.checking")}</p>}
              {avail === "ok" && <p className="mt-1 text-xs text-emerald-600">✓ {t("register.available")}</p>}
              {avail === "taken" && <p className="mt-1 text-xs text-red-600">{t("register.taken")}</p>}
              {avail === "invalid" && <p className="mt-1 text-xs text-red-600">{t("register.invalidSlug")}</p>}
            </div>
          )}
        </div>
        <details className="rounded-xl border border-slate-200 p-3">
          <summary className="cursor-pointer text-sm font-medium text-slate-600">{type === "STORE" ? t("register.category") : t("register.specialty")} <span className="text-slate-400">({t("register.optional")})</span></summary>
          <div className="mt-3">
            {catOptions.data ? <CategoryPicker key={type} options={catOptions.data} value={cats} onChange={setCats} other={otherCat} onOther={setOtherCat} /> : <p className="text-sm text-slate-500">{t("register.checking")}</p>}
          </div>
        </details>
        <ErrorText error={create.error} />
        <Button type="submit" loading={create.loading} disabled={!valid} className="w-full">{t("register.create")}</Button>
        <p className="text-center text-xs text-slate-500">{t("register.reassure")}</p>
      </form>
    </div>
  );
}
