import type { Metadata } from "next";
import { getT } from "@/lib/i18n-server";

export const metadata: Metadata = { title: "Privacy policy · سياسة الخصوصية" };

const EMAIL = "developer.elnemr@gmail.com";

type Section = { h: string; p: string[] };

const AR: Section[] = [
  { h: "من نحن", p: ["المنصّة هي خدمة تتيح للعيادات والمتاجر إدارة عملها، وتطبيقا «المنصّة» و«المنصّة للأعمال» للهاتف. هذه السياسة تشرح البيانات التي نجمعها وكيف نستخدمها."] },
  { h: "البيانات التي نجمعها", p: [
    "العملاء (المتاجر): الاسم ورقم الموبايل وعنوان التوصيل عند إتمام طلب، وحساب جوجل (البريد والاسم) إذا اخترت تسجيل الدخول به، وقائمة المفضلة وعناوينك المحفوظة.",
    "المرضى: تُنشأ حسابات المرضى بواسطة العيادة. تحتفظ العيادة ببياناتك الطبية (مواعيد، زيارات، ملاحظات) وهي المسؤولة عنها. لحذف بياناتك الطبية تواصل مع عيادتك مباشرة.",
    "أصحاب المتاجر والعيادات والعاملون: بيانات الحساب والعمل التي يدخلونها (منتجات، طلبات، مرضى، فواتير).",
    "بيانات تقنية: رمز الإشعارات الخاص بجهازك لإرسال تنبيهات الطلبات والمواعيد، وسجلات تقنية عامة لحماية الخدمة."] },
  { h: "كيف نستخدمها", p: ["لتنفيذ الطلبات والتوصيل، وإرسال تنبيهات حالة الطلب والمواعيد، وتسجيل الدخول، وحماية الخدمة من إساءة الاستخدام. لا نبيع بياناتك ولا نعرض إعلانات ولا نستخدم أدوات تتبّع إعلانية داخل التطبيقات."] },
  { h: "المشاركة", p: ["يرى المتجر الذي تطلب منه اسمك ورقمك وعنوانك لتجهيز طلبك وتوصيله، وقد يشاركها مع شركة الشحن. نستخدم مزوّدين تقنيين (استضافة، Google لتسجيل الدخول، Firebase لإرسال الإشعارات) لتشغيل الخدمة فقط."] },
  { h: "الاحتفاظ بالبيانات وحذفها", p: ["يمكنك حذف حساب العميل من داخل التطبيق (الطلبات ← حذف حسابي) أو من صفحة «حذف الحساب». يُحذف تسجيل الدخول وبيانات الاتصال والعناوين والمفضلة والأجهزة، وتبقى الطلبات السابقة لدى المتاجر لأغراضها المحاسبية والمرتجعات. بيانات المرضى تُدار عبر العيادة."] },
  { h: "الأمان", p: ["تُنقل البيانات عبر اتصال مشفّر (HTTPS) ولا تُحفظ كلمات المرور إلا مشفّرة. لا يوجد نظام آمن بنسبة مئة بالمئة، لكننا نبذل جهدًا معقولًا لحمايتها."] },
  { h: "الأطفال", p: ["الخدمة غير موجّهة للأطفال دون 13 سنة، وتُدار بيانات القاصرين من المرضى بواسطة العيادة وولي الأمر."] },
  { h: "التواصل", p: [`لأي استفسار أو طلب بخصوص بياناتك: ${EMAIL}`, "قد نحدّث هذه السياسة، وسيظهر تاريخ آخر تحديث أدناه."] },
];

const EN: Section[] = [
  { h: "Who we are", p: ["The platform lets clinics and shops run their business, and provides the “Elmanassa” and “Elmanassa Business” mobile apps. This policy explains what data we collect and how we use it."] },
  { h: "What we collect", p: [
    "Shoppers: name, mobile number and delivery address when you place an order; your Google account (email and name) if you choose to sign in with it; your wishlist and saved addresses.",
    "Patients: patient accounts are created by the clinic. The clinic keeps your medical data (appointments, visits, notes) and is responsible for it. To remove medical data, contact your clinic directly.",
    "Shop and clinic owners and staff: account and business data they enter (products, orders, patients, invoices).",
    "Technical data: your device's notification token (to send order and appointment alerts) and general technical logs used to protect the service."] },
  { h: "How we use it", p: ["To fulfil orders and delivery, send order-status and appointment alerts, sign you in, and protect the service from abuse. We do not sell your data, show ads, or use advertising trackers inside the apps."] },
  { h: "Sharing", p: ["The shop you order from sees your name, number and address to prepare and deliver your order, and may pass them to its courier. We use technical providers (hosting, Google sign-in, Firebase notifications) only to run the service."] },
  { h: "Retention and deletion", p: ["You can delete a shopper account inside the app (Orders → Delete my account) or on the “Delete account” page. Your login, contact details, addresses, wishlist and devices are removed; past orders remain with the shops for their accounting and returns. Patient data is managed by the clinic."] },
  { h: "Security", p: ["Data travels over encrypted connections (HTTPS) and passwords are stored only in hashed form. No system is perfectly secure, but we take reasonable measures to protect your data."] },
  { h: "Children", p: ["The service is not directed at children under 13; minors’ patient data is managed by the clinic and guardian."] },
  { h: "Contact", p: [`For any question or request about your data: ${EMAIL}`, "We may update this policy; the last-updated date is shown below."] },
];

export default async function Privacy() {
  const { locale } = await getT();
  const ar = locale === "ar";
  const sections = ar ? AR : EN;
  return (
    <div className="mx-auto max-w-3xl px-4 py-12">
      <h1 className="text-gradient text-3xl font-extrabold">{ar ? "سياسة الخصوصية" : "Privacy policy"}</h1>
      <p className="mt-2 text-sm text-slate-500">{ar ? "آخر تحديث: 9 أكتوبر 2026" : "Last updated: 9 October 2026"}</p>
      {sections.map((s) => (
        <section key={s.h} className="mt-8">
          <h2 className="text-lg font-bold text-ink">{s.h}</h2>
          {s.p.map((x) => <p key={x} className="mt-2 leading-7 text-slate-700">{x}</p>)}
        </section>
      ))}
    </div>
  );
}
