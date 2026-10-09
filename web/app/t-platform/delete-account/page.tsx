import type { Metadata } from "next";
import { getT } from "@/lib/i18n-server";

export const metadata: Metadata = { title: "Delete account · حذف الحساب" };

const EMAIL = "developer.elnemr@gmail.com";

export default async function DeleteAccount() {
  const { locale } = await getT();
  const ar = locale === "ar";
  const mail = `mailto:${EMAIL}?subject=${encodeURIComponent(ar ? "طلب حذف حساب" : "Account deletion request")}`;
  return (
    <div className="mx-auto max-w-3xl px-4 py-12">
      <h1 className="text-gradient text-3xl font-extrabold">{ar ? "حذف الحساب" : "Delete your account"}</h1>

      <section className="mt-8">
        <h2 className="text-lg font-bold text-ink">{ar ? "عميل المتاجر (تسجيل الدخول بجوجل)" : "Shoppers (Google sign-in)"}</h2>
        <ol className="mt-2 list-decimal space-y-1 ps-6 leading-7 text-slate-700">
          {ar
            ? <><li>افتح تطبيق «المنصّة» وادخل على أي متجر.</li><li>من تبويب «الطلبات» اضغط «حذف حسابي» ثم أكّد.</li></>
            : <><li>Open the Elmanassa app and enter any shop.</li><li>In the Orders tab tap “Delete my account” and confirm.</li></>}
        </ol>
        <p className="mt-3 leading-7 text-slate-700">
          {ar
            ? "يُحذف تسجيل الدخول وبيانات الاتصال والعناوين والمفضلة والأجهزة فورًا. تبقى الطلبات السابقة لدى المتاجر لأغراض المحاسبة والمرتجعات."
            : "Your login, contact details, addresses, wishlist and devices are deleted immediately. Past orders stay with the shops for their accounting and returns."}
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-lg font-bold text-ink">{ar ? "لا تستطيع استخدام التطبيق؟" : "Can’t use the app?"}</h2>
        <p className="mt-2 leading-7 text-slate-700">
          {ar ? "راسلنا من بريدك المسجّل ونحذف الحساب خلال 30 يومًا كحدّ أقصى:" : "Email us from your registered address and we will delete the account within 30 days at most:"}{" "}
          <a href={mail} className="font-semibold text-brand underline">{EMAIL}</a>
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-lg font-bold text-ink">{ar ? "المرضى وأصحاب المتاجر والعيادات" : "Patients, shop and clinic owners"}</h2>
        <p className="mt-2 leading-7 text-slate-700">
          {ar
            ? "حسابات المرضى تُنشأ وتُدار بواسطة العيادة، وهي تحتفظ بالسجل الطبي وفق القانون، فتواصل مع عيادتك لطلب الحذف. أصحاب المتاجر والعيادات يمكنهم مراسلتنا على البريد نفسه لإغلاق الاشتراك وحذف بياناته."
            : "Patient accounts are created and managed by the clinic, which keeps medical records as the law requires, so ask your clinic to delete yours. Shop and clinic owners can email us at the same address to close their subscription and delete its data."}
        </p>
      </section>
    </div>
  );
}
// Public page required by Google Play (account deletion URL).

