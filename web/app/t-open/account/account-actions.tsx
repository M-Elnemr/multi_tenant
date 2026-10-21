"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { errMsg, oapi } from "@/lib/open-client";

export function AccountActions({ name, phone }: { name: string; phone: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [n, setN] = useState(name);
  const [p, setP] = useState(phone);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const run = async (fn: () => Promise<unknown>, ok: string) => { setMsg(null); try { await fn(); setMsg({ ok: true, text: ok }); router.refresh(); } catch (e) { setMsg({ ok: false, text: errMsg(t, e) }); } };
  const out = async () => { await oapi("auth/logout", { method: "POST", body: {} }).catch(() => {}); router.replace("/"); router.refresh(); };
  return (
    <div className="mt-6 grid gap-4">
      <form onSubmit={(e) => { e.preventDefault(); void run(() => oapi("me", { method: "PUT", body: { name: n, phone: p || undefined } }), t("open.account.saved")); }} className="o-card grid gap-3 p-5">
        <h2 className="font-extrabold">{t("open.account.profile")}</h2>
        <div><label className="o-label" htmlFor="ac-name">{t("open.checkout.name")}</label><input id="ac-name" value={n} onChange={(e) => setN(e.target.value)} className="o-input" required /></div>
        <div><label className="o-label" htmlFor="ac-phone">{t("open.checkout.phone")}</label><input id="ac-phone" value={p} onChange={(e) => setP(e.target.value)} type="tel" inputMode="tel" dir="ltr" placeholder="01xxxxxxxxx" className="o-input" /><p className="o-mute mt-1 text-xs">{t("open.account.phoneHint")}</p></div>
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
