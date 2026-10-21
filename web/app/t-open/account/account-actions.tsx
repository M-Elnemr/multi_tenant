"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { errMsg, oapi } from "@/lib/open-client";

export function AccountActions({ name }: { name: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [n, setN] = useState(name);
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const run = async (fn: () => Promise<unknown>, ok: string) => { setMsg(null); try { await fn(); setMsg({ ok: true, text: ok }); router.refresh(); } catch (e) { setMsg({ ok: false, text: errMsg(t, e) }); } };
  const out = async () => { await oapi("auth/logout", { method: "POST", body: {} }).catch(() => {}); router.replace("/"); router.refresh(); };
  return (
    <div className="mt-6 grid gap-4">
      <form onSubmit={(e) => { e.preventDefault(); void run(() => oapi("me", { method: "PUT", body: { name: n } }), t("open.account.saved")); }} className="o-card grid gap-3 p-5">
        <h2 className="font-extrabold">{t("open.account.profile")}</h2>
        <input value={n} onChange={(e) => setN(e.target.value)} aria-label={t("open.checkout.name")} className="o-input" required />
        <button className="o-btn o-btn-dark o-btn-sm w-fit">{t("common.save")}</button>
      </form>
      <form onSubmit={(e) => { e.preventDefault(); void run(async () => { await oapi("me/password", { body: { currentPassword: cur, newPassword: next } }); setCur(""); setNext(""); }, t("open.account.passwordChanged")); }} className="o-card grid gap-3 p-5">
        <h2 className="font-extrabold">{t("open.account.changePassword")}</h2>
        <input type="password" value={cur} onChange={(e) => setCur(e.target.value)} placeholder={t("open.account.currentPassword")} autoComplete="current-password" className="o-input" required />
        <input type="password" value={next} onChange={(e) => setNext(e.target.value)} placeholder={t("open.account.newPassword")} autoComplete="new-password" minLength={8} className="o-input" required />
        <button className="o-btn o-btn-dark o-btn-sm w-fit">{t("common.save")}</button>
      </form>
      {msg && <p role="status" className={`rounded-xl px-3.5 py-2.5 text-sm font-bold ${msg.ok ? "bg-[#e8f8f0] text-[#067647]" : "bg-[#fef3f2] text-[#b42318]"}`}>{msg.text}</p>}
      <div className="flex flex-wrap gap-3">
        <button type="button" onClick={out} className="o-btn o-btn-ghost">{t("open.account.signOut")}</button>
        <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="o-btn o-btn-ghost">{locale === "ar" ? "English" : "العربية"}</a>
        <button type="button" onClick={() => { if (window.confirm(t("open.account.deleteConfirm"))) void oapi("me", { method: "DELETE" }).then(() => { router.replace("/"); router.refresh(); }).catch((e) => setMsg({ ok: false, text: errMsg(t, e) })); }} className="ms-auto text-sm font-bold text-[var(--o-red)] hover:underline">{t("open.account.delete")}</button>
      </div>
    </div>
  );
}
