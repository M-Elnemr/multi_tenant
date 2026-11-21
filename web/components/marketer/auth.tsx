"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Field, Input } from "@/components/ui";
import { errText } from "@/components/dashboard/marketing/kit";

/** Marketer sign-in and sign-up (phone + password). The session lives in an httpOnly cookie set by the web layer. */
export function MarketerAuth() {
  const { locale } = useI18n();
  const L = (ar: string, en: string) => (locale === "ar" ? ar : en);
  const [mode, setMode] = useState<"login" | "register">("register");
  const [name, setName] = useState(""), [phone, setPhone] = useState(""), [password, setPassword] = useState("");
  const [err, setErr] = useState<string | null>(null), [busy, setBusy] = useState(false);
  const go = async () => {
    setBusy(true); setErr(null);
    try {
      await api(mode === "login" ? "marketer/login" : "marketer/register", { body: mode === "login" ? { phone, password } : { name, phone, password } });
      window.location.assign("/marketer/app");
    } catch (e) { setErr(errText(e, L("حصلت مشكلة، جرّب تاني", "Something went wrong, try again"))); } finally { setBusy(false); }
  };
  return (
    <div className="mx-auto grid min-h-[70dvh] max-w-5xl items-center gap-10 px-4 py-10 lg:grid-cols-2">
      <div>
        <p className="text-sm font-bold text-emerald-600">{L("برنامج المسوّقين", "Marketer program")}</p>
        <h1 className="mt-2 text-3xl font-extrabold leading-tight sm:text-5xl">{L("اكسب عمولة من كل طلب بتجيبه لمتاجر المنصة", "Earn a commission on every order you bring to Elmanassa shops")}</h1>
        <ul className="mt-6 space-y-3 text-slate-600">
          <li>✓ {L("اختار متاجر من السوق وقدّم عليها", "Pick shops from the marketplace and apply")}</li>
          <li>✓ {L("رابط خاص بيك وتتبّع لكل حملة", "Your own link and tracking for every campaign")}</li>
          <li>✓ {L("العمولة بتتحسب بس على الطلبات اللي وصلت فعلاً", "Commission only on orders that really arrive")}</li>
          <li>✓ {L("اطلب سحب رصيدك والمتجر بيدفعلك مباشرة", "Request a payout; the shop pays you directly")}</li>
        </ul>
        <p className="mt-4 text-xs text-slate-400">{L("المنصة مش بتمسك فلوسك: المتجر هو اللي بيدفع.", "The platform never holds your money: the shop pays you.")}</p>
      </div>
      <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex gap-2">{(["register", "login"] as const).map((m) => <button key={m} onClick={() => setMode(m)} className={`min-h-11 flex-1 rounded-xl text-sm font-bold ${mode === m ? "bg-slate-900 text-white" : "bg-slate-100 text-slate-600"}`}>{m === "register" ? L("حساب جديد", "Sign up") : L("دخول", "Sign in")}</button>)}</div>
        <div className="space-y-3">
          {mode === "register" && <Field label={L("الاسم", "Name")}><Input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" /></Field>}
          <Field label={L("رقم الموبايل", "Mobile number")}><Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" dir="ltr" autoComplete="tel" /></Field>
          <Field label={L("كلمة السر (٨ حروف على الأقل)", "Password (8+ characters)")}><Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete={mode === "login" ? "current-password" : "new-password"} /></Field>
          {err && <Alert>{err}</Alert>}
          <Button className="w-full" loading={busy} disabled={!phone || password.length < 8 || (mode === "register" && !name.trim())} onClick={() => void go()}>{mode === "register" ? L("إنشاء الحساب", "Create account") : L("دخول", "Sign in")}</Button>
        </div>
      </div>
    </div>
  );
}
