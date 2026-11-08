"use client";

import { localPhone } from "@/lib/phone";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { PhoneInput } from "@/components/inputs";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Menu, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { SearchBox } from "@/components/dashboard/filters";
import { Avatar, Button, Empty, ErrorText, Field, Input, Loading, PageHeader, Pager, SecretBox, Select } from "@/components/ui";
import { MoreTrigger, Paper, Pill, SageTile } from "@/components/clinic/dash";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { usePatientPortal } from "@/components/portal-flag";
import { ageText } from "@/lib/format";
import { QrScan } from "@/components/qr-scan";

type P = { id: string; patientCode: string; firstName: string; lastName: string; ageYears?: number; ageMonths?: number; sex?: string; phone?: string; hasPortal: boolean; publicId?: number; dependents?: P[] };
type Created = { id: string; patientCode: string; portalAccess: string; firstName: string; password?: string };

export default function Patients() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { can } = useMe();
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const rq = (b: string) => { const qs = rangeQuery(new URLSearchParams(), range).toString(); return qs ? `${b}${b.includes("?") ? "&" : "?"}${qs}` : b; };
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<P>>(rq(`clinic/patients?page=${page}&q=${encodeURIComponent(q)}`));
  const [open, setOpen] = useState(false);
  const portal = usePatientPortal();
  const clinic = useApi<{ clinicName?: string }>("clinic/public/profile");
  const blank = { firstName: "", phone: "", ageYears: "", ageMonths: "0", sex: "" };
  const [f, setF] = useState(blank);
  const [pw, setPw] = useState("");
  const [created, setCreated] = useState<Created | null>(null);
  const [depOf, setDepOf] = useState<P | null>(null);   // adding a child under this patient (no phone, no password)
  // "Existing patient" tab: find a patient who already uses the platform by ID, phone or scan, and add them to this clinic
  const [tab, setTab] = useState<"new" | "existing">("new");
  const [code, setCode] = useState("");
  const [scanning, setScanning] = useState(false);
  const [found, setFound] = useState<{ found: boolean; displayName?: string; alreadyInClinic?: boolean; patientId?: string; publicId?: number } | null>(null);
  const lookup = useAction(async (c?: string) => {
    const v = (c ?? code).trim();
    if (!v) return;
    setFound(await api(`clinic/patients/lookup?q=${encodeURIComponent(v)}`));
  });
  const addExisting = useAction(async () => {
    const r = await api<Created>("clinic/patients/link", { body: { q: code.trim() } });
    setCreated({ ...r, password: undefined });
    setOpen(false); setFound(null); setCode(""); setTab("new");
    await reload();
    toast(t("cld.patientAdded"));
  });
  const create = useAction(async () => {
    const r = await api<Created>("clinic/patients", { body: { firstName: f.firstName.trim(), phone: depOf ? undefined : f.phone, guardianPatientId: depOf?.id, relationship: depOf ? "PARENT" : undefined, ageYears: Number(f.ageYears), ageMonths: Number(f.ageMonths || 0), sex: f.sex || undefined, initialPassword: portal && pw ? pw : undefined } });
    setCreated({ ...r, password: portal && r.portalAccess === "PASSWORD_SET" ? pw : undefined });
    setPw(""); setOpen(false); setDepOf(null); setF(blank);
    await reload();
    toast(t("cld.patientAdded"));
  });
  const openAdd = () => { setDepOf(null); setTab("new"); setFound(null); setCode(""); setOpen(true); };
  const rows = data?.data ?? [];

  const person = (p: P, child?: boolean) => (
    <Link key={p.id} href={`/dashboard/patients/${p.id}`} className="flex min-w-0 items-center gap-3">
      <Avatar name={`${p.firstName} ${p.lastName}`} className={child ? "h-8 w-8 text-xs" : "h-10 w-10 text-[13px]"} />
      <span className="min-w-0">
        <span className="block truncate font-semibold text-ink">{p.firstName} {p.lastName}</span>
        <span className="block font-mono text-[11px] text-faint">{p.patientCode}</span>
      </span>
    </Link>
  );

  return (
    <>
      <PageHeader eyebrow={t("rail.patients")} title={t("nav.patients")} subtitle={t("cld.patientsSubtitle")}
        actions={can("patient.create") && <Button onClick={openAdd}><Icon name="plus" className="h-4 w-4" />{t("patients.add")}</Button>} />
      <div className="space-y-4">
        {created && (
          <div className="animate-fade-up space-y-3 rounded-[18px] bg-[color-mix(in_srgb,var(--rail-accent)_8%,var(--surface))] p-4 shadow-card sm:p-5">
            <div className="flex items-start justify-between gap-3">
              <p className="flex items-start gap-2.5 text-sm text-ink"><SageTile icon="check" className="h-8 w-8" /><span className="pt-1">{t("patients.created", { name: created.firstName, code: created.patientCode })}</span></p>
              <button type="button" onClick={() => setCreated(null)} aria-label={t("common.close")} className="grid h-8 w-8 shrink-0 place-items-center rounded-[9px] text-muted hover:bg-surface-2"><Icon name="close" className="h-4 w-4" /></button>
            </div>
            {portal && created.password && <SecretBox label={t("patients.passwordSet")} value={created.password} />}
            {portal && <p className="text-sm text-muted">{created.portalAccess === "PASSWORD_SET" ? t("patients.howPassword") : created.portalAccess === "ASSIGNED" ? t("patients.howAssigned") : created.portalAccess === "GUARDIAN" ? t("patients.howGuardian") : t("patients.noPortal")}</p>}
          </div>
        )}

        <div className="flex flex-wrap items-center gap-2.5 rounded-[18px] bg-surface p-2.5 shadow-card">
          <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("patients.searchHint")} />
          <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} label={t("patients.registered")} />
          {data?.meta && <span className="ms-auto px-2 text-xs text-muted tnum">{data.meta.total}</span>}
        </div>
        <ErrorText error={error} />

        {loading && !data ? <Loading variant="table" /> : rows.length === 0 ? (
          <Empty icon="patients" title={q || range.from || range.to ? t("cld.noMatch") : t("cld.noPatients")} action={can("patient.create") && !q ? <Button variant="secondary" onClick={openAdd}><Icon name="plus" className="h-4 w-4" />{t("patients.add")}</Button> : undefined}>{t("cld.noPatientsHint")}</Empty>
        ) : (
          <>
            <Paper className="overflow-hidden">
              <div className="hidden grid-cols-[minmax(0,2.2fr)_1.3fr_0.8fr_auto] gap-4 border-b border-line bg-surface-2 px-5 py-3 text-[11.5px] font-semibold text-muted sm:grid">
                <span>{t("clinic.patient")}</span><span>{t("patients.mobile")}</span><span>{t("patients.age")}</span><span className="w-20 text-end">{portal ? t("patients.portal") : ""}</span>
              </div>
              <ul className="divide-y divide-line">
                {rows.map((p) => (
                  <li key={p.id}>
                    <div className="grid items-center gap-x-4 gap-y-2 px-4 py-3 transition hover:bg-surface-2/70 sm:grid-cols-[minmax(0,2.2fr)_1.3fr_0.8fr_auto] sm:px-5">
                      {person(p)}
                      <span className="flex items-center gap-2 text-[13.5px] text-ink-2"><span dir="ltr" className="tnum">{localPhone(p.phone) || "-"}</span><WhatsAppButton phone={localPhone(p.phone)} message={t("pq.waHello", { name: p.firstName, clinic: clinic.data?.clinicName ?? "" })} size={28} /></span>
                      <span className="text-[13.5px] text-muted">{ageText(p.ageYears, p.ageMonths, locale)}</span>
                      <span className="flex items-center justify-end gap-2 sm:w-20">
                        {portal && (p.hasPortal ? <Pill tone="sage">{t("patients.portal")}</Pill> : <span className="text-faint">—</span>)}
                        {portal && p.hasPortal && can("patient.create") && <Menu label={t("cld.moreActions")} items={[{ label: t("dep.add"), icon: "plus", onSelect: () => { setDepOf(p); setOpen(true); } }]} trigger={({ toggle }) => <MoreTrigger toggle={toggle} label={t("cld.moreActions")} />} />}
                      </span>
                    </div>
                    {(p.dependents ?? []).map((c) => (
                      <div key={c.id} className="grid items-center gap-x-4 gap-y-1 bg-surface-2/50 py-2.5 pe-4 ps-10 sm:grid-cols-[minmax(0,2.2fr)_1.3fr_0.8fr_auto] sm:pe-5 sm:ps-14">
                        <div className="flex min-w-0 items-center gap-2"><Icon name="arrow" className="h-3.5 w-3.5 shrink-0 text-faint flip-rtl" />{person(c, true)}</div>
                        <span className="text-xs text-muted">{t("dep.childOf", { name: p.firstName })}</span>
                        <span className="text-[13px] text-muted">{ageText(c.ageYears, c.ageMonths, locale)}</span>
                        <span className="sm:w-20" />
                      </div>
                    ))}
                  </li>
                ))}
              </ul>
            </Paper>
            <Pager meta={data?.meta} onPage={setPage} />
          </>
        )}
      </div>

      <Sheet open={open} onClose={() => { setOpen(false); setScanning(false); }} title={depOf ? t("dep.addTitle", { name: depOf.firstName }) : t("patients.add")} width="30rem">
        {!depOf && portal && (
          <div className="mb-4 grid grid-cols-2 gap-1 rounded-[12px] bg-[color-mix(in_srgb,var(--ink)_5%,transparent)] p-1 text-sm font-semibold">
            {(["new", "existing"] as const).map((k) => <button key={k} type="button" onClick={() => { setTab(k); setScanning(false); }} className={`rounded-[9px] py-2 transition ${tab === k ? "bg-surface text-ink shadow-sm" : "text-muted"}`}>{k === "new" ? t("patients.newTab") : t("patients.existingTab")}</button>)}
          </div>
        )}
        {tab === "existing" && !depOf ? (
          <div className="space-y-3">
            {scanning ? <QrScan onClose={() => setScanning(false)} onCode={(c) => { setScanning(false); setCode(c); void lookup.run(c); }} /> : (
              <>
                <Field label={t("patients.findHint")}>
                  <div className="flex gap-2">
                    <Input value={code} onChange={(e) => { setCode(e.target.value); setFound(null); }} inputMode="numeric" dir="ltr" placeholder="123456" onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void lookup.run(); } }} />
                    <Button type="button" variant="secondary" loading={lookup.loading} onClick={() => void lookup.run()}>{t("patients.find")}</Button>
                  </div>
                </Field>
                <Button type="button" variant="secondary" className="w-full" onClick={() => setScanning(true)}><Icon name="qr" className="h-4 w-4" />{t("patients.scan")}</Button>
                {found && !found.found && <p className="rounded-[12px] bg-amber-50 p-3 text-sm text-amber-900 ring-1 ring-inset ring-amber-600/20">{t("patients.notFound")}</p>}
                {found?.found && (
                  <div className="space-y-3 rounded-[14px] bg-[color-mix(in_srgb,var(--rail-accent)_8%,var(--surface))] p-4 ring-1 ring-inset ring-line">
                    <div className="flex items-center gap-3"><Avatar name={found.displayName ?? ""} className="h-11 w-11 text-sm" /><div><p className="font-display text-lg font-semibold text-ink">{found.displayName}</p><p className="font-mono text-xs text-muted" dir="ltr">{found.publicId}</p></div></div>
                    {found.alreadyInClinic ? (
                      <>
                        <p className="text-sm text-emerald-700">{t("patients.alreadyYours")}</p>
                        <Link href={`/dashboard/patients/${found.patientId}`} className="inline-flex items-center gap-1 font-semibold text-[var(--rail-accent-ink)]">{t("patients.openPatient")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
                      </>
                    ) : <Button type="button" className="w-full" loading={addExisting.loading} onClick={() => void addExisting.run()}>{t("patients.addExisting")}</Button>}
                  </div>
                )}
                <ErrorText error={lookup.error ?? addExisting.error} />
              </>
            )}
          </div>
        ) : (
        <form onSubmit={(e) => { e.preventDefault(); void create.run(); }} className="space-y-4">
          <Field label={t("patients.name")}><Input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} required maxLength={100} /></Field>
          {depOf ? <p className="rounded-[12px] bg-surface-2 p-3 text-sm text-muted ring-1 ring-inset ring-line">{t("dep.noPhoneHint")}</p> : <Field label={t("patients.mobile")} hint={t("patients.mobileHint")}><PhoneInput value={f.phone} onValue={(phone) => setF({ ...f, phone })} required /></Field>}
          <p className="text-[13px] font-semibold text-ink-2">{t("patients.age")} <span className="font-normal text-muted">· {t("patients.ageHint")}</span></p>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("patients.years")}><Input type="number" inputMode="numeric" min={0} max={130} step={1} value={f.ageYears} onChange={(e) => setF({ ...f, ageYears: e.target.value })} required dir="ltr" /></Field>
            <Field label={t("patients.months")}><Input type="number" inputMode="numeric" min={0} max={11} step={1} value={f.ageMonths} onChange={(e) => setF({ ...f, ageMonths: String(Math.min(11, Math.max(0, Math.floor(Number(e.target.value) || 0)))) })} dir="ltr" /></Field>
          </div>
          <div className="grid grid-cols-2 gap-3"><Field label={t("patients.sex")}><Select value={f.sex} onChange={(e) => setF({ ...f, sex: e.target.value })}><option value="">-</option><option value="F">{t("patients.female")}</option><option value="M">{t("patients.male")}</option></Select></Field></div>
          {portal && !depOf && f.phone && (
            <div className="space-y-2 rounded-[14px] bg-surface-2 p-4 text-sm ring-1 ring-inset ring-line">
              <p className="font-semibold text-ink">{t("patients.portalAccess")}</p>
              <Field label={t("patients.tempPassword")} hint={t("patients.tempPasswordHint")}><Input value={pw} onChange={(e) => setPw(e.target.value)} minLength={8} dir="ltr" autoComplete="off" /></Field>
              <p className="text-xs text-muted">{t("patients.existingHint")}</p>
            </div>
          )}
          <ErrorText error={create.error} />
          <Button type="submit" loading={create.loading} className="w-full">{t("common.save")}</Button>
        </form>
        )}
      </Sheet>
    </>
  );
}
