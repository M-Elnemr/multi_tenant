"use client";

import Link from "next/link";
import { ChannelIcon } from "../../channel-icons";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { Alert, Button, ErrorText } from "../../ui";
import { money } from "@/lib/format";
import { Panel, PanelHead } from "../marketing/kit";
import { PLATFORMS, WA_OWN_LINK, type ByPlatform } from "./types";

/** A phone-shaped preview of the message as the customer would read it. */
function Bubble({ text, image }: { text: string; image: string | null }) {
  const { t } = useI18n();
  return (
    <div className="rounded-[18px] bg-[#efeae2] p-3 ring-1 ring-line dark:bg-[#0b141a]">
      <div className="ms-auto w-fit max-w-[92%] rounded-[12px] rounded-te-[4px] bg-[#d9fdd3] px-3 py-2 text-[13px] leading-relaxed text-[#111b21] shadow-sm">
        {image && <span className="mb-1.5 grid h-24 w-44 place-items-center rounded-[8px] bg-black/10 text-[#111b21]/50"><Icon name="camera" className="h-6 w-6" /></span>}
        <p className="whitespace-pre-wrap break-words">{text.trim() || <span className="opacity-50">{t("grx.previewEmpty")}</span>}</p>
      </div>
    </div>
  );
}

function Line({ label, value, strong }: { label: string; value: React.ReactNode; strong?: boolean }) {
  return <div className="flex items-baseline justify-between gap-3 py-1.5 text-[13px]"><span className="text-muted">{label}</span><span className={`tnum ${strong ? "font-display text-[15px] font-semibold text-ink" : "font-medium text-ink-2"}`}>{value}</span></div>;
}

/** Sticky side card: preview, audience per platform, reachable, cost, balance, duration and the send button. */
export default function Summary({ msg, image, plats, byPlatform, reachable, outsideWindow, priceMinor, balanceMinor, ownLinked, ready, sending, error, onSend }: {
  msg: string; image: string | null; plats: string[]; byPlatform: ByPlatform | null; reachable: number; outsideWindow: number;
  priceMinor: number; balanceMinor: number; ownLinked: boolean; ready: boolean; sending: boolean; error: unknown; onSend: () => void;
}) {
  const { t, locale, currency } = useI18n();
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const cost = reachable * priceMinor;
  const low = cost > balanceMinor;
  const noNumber = plats.includes("WHATSAPP") && !ownLinked;
  // one message every 5 to 15 seconds: 10 seconds on average
  const mins = Math.max(1, Math.ceil((reachable * 10) / 60));
  const duration = mins >= 120 ? t("gx.hours", { n: n(Math.ceil(mins / 60)) }) : t("gx.minutes", { n: n(mins) });
  return (
    <Panel className="space-y-4 p-5">
      <PanelHead icon="eye" title={t("grx.summary")} />
      <Bubble text={msg} image={image} />
      <div className="divide-y divide-line rounded-[14px] bg-canvas px-3.5 py-1.5 ring-1 ring-line">
        {PLATFORMS.filter((p) => plats.includes(p)).map((p) => (
          <Line key={p} label={t(`gx.plat.${p}`)} value={<span className="inline-flex items-center gap-1.5"><ChannelIcon channel={p} className="h-3.5 w-3.5" />{byPlatform?.[p] ? t("gx.platCount", { reachable: n(byPlatform[p]!.reachable), total: n(byPlatform[p]!.total) }) : "-"}</span>} />
        ))}
        <Line label={t("gx.sumReachable")} value={n(reachable)} />
        {outsideWindow > 0 && <Line label={t("gx.sumOutside")} value={n(outsideWindow)} />}
        <Line label={t("gx.sumCost")} value={money(cost, currency, locale)} strong />
        <Line label={t("gx.sumBalance")} value={money(balanceMinor, currency, locale)} />
        <Line label={t("gx.sumDuration")} value={reachable > 0 ? duration : "-"} />
      </div>
      <p className="text-[12px] leading-relaxed text-muted">{t("gx.priceLine", { piasters: n(priceMinor), egp: (priceMinor / 100).toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { minimumFractionDigits: 1, maximumFractionDigits: 2 }) })}</p>
      <p className="text-[12px] leading-relaxed text-muted">{t("gx.pacing")}</p>
      {low && reachable > 0 && <Alert tone="red">{t("gx.noCredit", { need: money(cost, currency, locale), balance: money(balanceMinor, currency, locale) })} <Link href="/dashboard/ai" className="font-semibold underline">{t("gr.topUp")}</Link></Alert>}
      {noNumber && <Alert tone="amber">{t("gx.noNumberAlert")} <Link href={WA_OWN_LINK} className="font-semibold underline">{t("gx.connectNumber")}</Link></Alert>}
      <ErrorText error={error} />
      <Button variant="ink" size="lg" className="w-full" loading={sending} disabled={!ready || low || noNumber || reachable === 0} onClick={onSend}><Icon name="send" className="h-4 w-4 flip-rtl" />{t("gr.send")}</Button>
    </Panel>
  );
}
