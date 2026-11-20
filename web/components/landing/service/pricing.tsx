import Link from "next/link";
import { Icon } from "@/components/icons";
import { POINT_PRICES } from "@/lib/pricing";
import type { ServiceCopy } from "./copy";

/** Prices are the platform's own (lib/pricing.ts mirrors the backend): nothing is written into the copy. */
export function ServicePricing({ p, locale, register }: { p: ServiceCopy["pricing"]; locale: string; register: string }) {
  const nf = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { maximumFractionDigits: 1 });
  const egp = (n: number) => (locale === "ar" ? `${nf(n)} جنيه` : `EGP ${nf(n)}`);
  return (
    <div className="mt-12 grid gap-5 lg:grid-cols-[1fr_1fr]">
      <div className="rounded-[1.75rem] bg-[#1b1233] p-7 text-white">
        <p className="text-sm font-semibold text-white/60">{p.freeTitle}</p>
        <p className="mt-2 flex items-baseline gap-3"><span className="font-display text-6xl font-extrabold text-[#ff7aa3]">{p.freePrice}</span><span className="text-sm text-white/50">{p.freeNote}</span></p>
        <ul className="mt-6 space-y-3">{p.freeItems.map((x) => <li key={x} className="flex items-start gap-3 text-[15px] text-white/85"><Icon name="check" className="mt-0.5 h-5 w-5 shrink-0 text-[#ff7aa3]" />{x}</li>)}</ul>
        <div className="mt-6 flex items-start gap-3 rounded-2xl border border-[#ff7aa3]/30 bg-[#ff7aa3]/10 p-4"><Icon name="award" className="mt-0.5 h-5 w-5 shrink-0 text-[#ff7aa3]" /><div><p className="font-bold">{p.giftTitle}: {egp(POINT_PRICES.welcomePoints)}</p><p className="mt-0.5 text-sm text-white/65">{p.giftBody}</p></div></div>
        <Link href={register} className="lp-cta mt-7 w-full justify-center">{p.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
      </div>
      <div className="space-y-3 rounded-[1.75rem] border border-[#1b1233]/10 bg-white p-7">
        <div className="flex items-center gap-4 rounded-2xl bg-[#fff1f4] p-4"><span className="font-display text-2xl font-extrabold text-[#e0457b] tnum">{egp(POINT_PRICES.aiConversation)}</span><span className="text-sm font-semibold text-[#1b1233]/75">{p.rateAi} ({nf(POINT_PRICES.conversationReplies)})</span></div>
        <div className="flex items-center gap-4 rounded-2xl bg-[#f1eff5] p-4"><span className="font-display text-2xl font-extrabold text-[#1b1233] tnum">{egp(POINT_PRICES.message)}</span><span className="text-sm font-semibold text-[#1b1233]/75">{p.rateMsg}</span></div>
      </div>
    </div>
  );
}
