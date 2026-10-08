"use client";

import { useEffect, useState } from "react";
import { api, newKey } from "@/lib/client";
import { PhoneInput, EmailInput } from "@/components/inputs";
import { useAction, useApi } from "@/components/hooks";
import { CategoryPicker, categoriesValid, type CategoryOption } from "@/components/category-picker";
import { useT } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input } from "@/components/ui";

type Type = "STORE" | "CLINIC";

function slugify(s: string) {
  return s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40);
}

/** Create a store or a clinic in one screen. The site is live on its own subdomain the moment this finishes. */
export function RegisterWizard({ rootDomain, initialType }: { rootDomain: string; initialType: Type | null }) {
  const t = useT();
  const [type, setType] = useState<Type | null>(initialType);
  const [name, setName] = useState("");
  const [manualSlug, setManualSlug] = useState<string | null>(null);
  const slug = manualSlug ?? slugify(name);
  const [check, setCheck] = useState<{ slug: string; state: "ok" | "taken" | "invalid" }>({ slug: "", state: "ok" });
  const [owner, setOwner] = useState({ name: "", phone: "", email: "", password: "" });
  const [cats, setCats] = useState<string[]>([]);
  const [otherCat, setOtherCat] = useState("");
  const catOptions = useApi<CategoryOption[]>(type ? `onboarding/categories?type=${type}` : null);
  const [key] = useState(newKey);
  const [done, setDone] = useState<{ host: string; tenantId: string } | null>(null);
  const [opening, setOpening] = useState(false);

  // Debounced live availability check; state is only written after the response arrives.
  useEffect(() => {
    if (slug.length < 3) return;
    const h = setTimeout(async () => {
      try {
        const r = await api<{ available: boolean }>(`onboarding/slug-available?slug=${encodeURIComponent(slug)}`);
        setCheck({ slug, state: r.available ? "ok" : "taken" });
      } catch { setCheck({ slug, state: "invalid" }); }
    }, 350);
    return () => clearTimeout(h);
  }, [slug]);
  const avail: "idle" | "checking" | "ok" | "taken" | "invalid" = slug.length < 3 ? "idle" : check.slug === slug ? check.state : "checking";

  const siteUrl = (host: string) => {
    const port = window.location.port ? `:${window.location.port}` : "";
    return `${window.location.protocol}//${host}${port}`;
  };

  const create = useAction(async () => {
    const r = await api<{ host: string; tenant: { id: string } }>("onboarding/tenants", {
      body: { type, name: name.trim(), slug, ownerFirstName: owner.name.trim(), phone: owner.phone.trim(), email: owner.email.trim() || undefined, password: owner.password, categories: cats, otherCategory: cats.includes("other") ? otherCat.trim() : undefined },
      idempotencyKey: key,
    });
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

  if (done) {
    const base = siteUrl(done.host);
    return (
      <div className="mx-auto max-w-lg px-4 py-14 text-center">
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-2xl text-emerald-700">✓</div>
        <h1 className="text-2xl font-semibold">{t("register.liveTitle")}</h1>
        <p className="mt-2 text-slate-600">{t("register.liveBody")}</p>
        <p className="my-4 rounded-lg bg-white p-3 font-mono text-sm" dir="ltr">{done.host}</p>
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-center">
          <button type="button" onClick={() => void openDashboard()} disabled={opening} className="rounded-lg bg-brand px-5 py-3 text-sm font-medium text-white disabled:opacity-60">{opening ? t("myplaces.opening") : t("register.openDashboard")}</button>
          <a href={base} className="rounded-lg border border-slate-300 bg-white px-5 py-3 text-sm font-medium">{t("register.viewSite")}</a>
        </div>
        <p className="mt-6 text-sm text-slate-500">{t("register.domainTip")}</p>
      </div>
    );
  }

  if (!type) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-14">
        <h1 className="mb-6 text-center text-2xl font-semibold">{t("register.chooseType")}</h1>
        <div className="grid gap-4 sm:grid-cols-2">
          {([["STORE", "type.store", "register.storeDesc"], ["CLINIC", "type.clinic", "register.clinicDesc"]] as const).map(([v, title, desc]) => (
            <button key={v} onClick={() => setType(v)} className="rounded-2xl border-2 border-slate-200 bg-white p-6 text-start transition hover:border-brand">
              <p className="text-lg font-semibold">{t(title)}</p>
              <p className="mt-1 text-sm text-slate-500">{t(desc)}</p>
            </button>
          ))}
        </div>
      </div>
    );
  }

  const valid = name.trim().length >= 2 && avail === "ok" && owner.name.trim() && owner.phone.trim() && owner.password.length >= 8 && categoriesValid(cats, otherCat);
  return (
    <div className="mx-auto max-w-xl px-4 py-12">
      <button onClick={() => { setType(null); setCats([]); setOtherCat(""); }} className="mb-4 text-sm text-slate-500 underline">{t("common.back")}</button>
      <h1 className="mb-1 text-2xl font-semibold">{type === "STORE" ? t("register.storeTitle") : t("register.clinicTitle")}</h1>
      <p className="mb-6 text-sm text-slate-500">{t("register.freeTrial")}</p>
      <form onSubmit={(e) => { e.preventDefault(); if (valid) void create.run(); }} className="space-y-5 rounded-2xl border border-slate-200 bg-white p-6">
        <Field label={type === "STORE" ? t("register.storeName") : t("register.clinicName")}>
          <Input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />
        </Field>
        <Field label={t("register.address")} hint={t("register.addressHint")}>
          <div className="flex items-center gap-2" dir="ltr">
            <Input value={slug} onChange={(e) => setManualSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))} required minLength={3} maxLength={40} className="text-end" />
            <span className="whitespace-nowrap text-sm text-slate-500">.{rootDomain}</span>
          </div>
          {avail === "checking" && <p className="mt-1 text-xs text-slate-500">{t("register.checking")}</p>}
          {avail === "ok" && <p className="mt-1 text-xs text-emerald-600">✓ {t("register.available")}</p>}
          {avail === "taken" && <p className="mt-1 text-xs text-red-600">{t("register.taken")}</p>}
          {avail === "invalid" && <p className="mt-1 text-xs text-red-600">{t("register.invalidSlug")}</p>}
        </Field>
        <Field label={type === "STORE" ? t("register.category") : t("register.specialty")} hint={type === "STORE" ? t("register.categoryHint") : t("register.specialtyHint")}>
          {catOptions.data ? <CategoryPicker key={type} options={catOptions.data} value={cats} onChange={setCats} other={otherCat} onOther={setOtherCat} /> : <p className="text-sm text-slate-500">{t("register.checking")}</p>}
        </Field>
        <hr className="border-slate-100" />
        <Field label={t("register.ownerName")}><Input value={owner.name} onChange={(e) => setOwner({ ...owner, name: e.target.value })} required maxLength={100} autoComplete="name" /></Field>
        <Field label={t("register.phone")} hint={t("register.phoneHint")}><PhoneInput value={owner.phone} onValue={(phone) => setOwner({ ...owner, phone })} required /></Field>
        <Field label={t("register.email")} hint={t("register.emailHint")}><EmailInput value={owner.email} onValue={(email) => setOwner({ ...owner, email })} /></Field>
        <Field label={t("login.password")} hint={t("login.passwordRule")}><Input type="password" value={owner.password} onChange={(e) => setOwner({ ...owner, password: e.target.value })} required minLength={8} autoComplete="new-password" /></Field>
        <ErrorText error={create.error} />
        {avail === "taken" && <Alert tone="amber">{t("register.taken")}</Alert>}
        <Button type="submit" loading={create.loading} disabled={!valid} className="w-full">{t("register.create")}</Button>
      </form>
    </div>
  );
}
