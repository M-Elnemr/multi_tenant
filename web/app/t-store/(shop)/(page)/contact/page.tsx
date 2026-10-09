import { HoursList } from "@/components/shop/hours";
import type { ShopProfile } from "@/components/shop/types";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { whatsappLink } from "@/lib/whatsapp";

export default async function Contact() {
  const { t } = await getT();
  const p = (await backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null))?.profile;
  if (!p) return null;
  const phones = [p.supportPhone, ...(p.extraPhones ?? [])].filter(Boolean) as string[];
  const row = (icon: string, label: string, body: React.ReactNode) => (
    <div className="flex gap-4 border-b border-[var(--s-line)] py-4 last:border-0"><span className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl bg-[var(--s-soft)] text-xl">{icon}</span><div><p className="text-xs font-extrabold uppercase tracking-wider text-[var(--s-mute)]">{label}</p><div className="mt-0.5 font-semibold">{body}</div></div></div>
  );
  return (
    <div className="mx-auto max-w-4xl">
      <p className="s-eyebrow">{t("shop.contact")}</p>
      <h1 className="mt-1 text-3xl font-extrabold sm:text-4xl">{t("contact.title")}</h1>
      <p className="mt-2 max-w-xl text-[var(--s-mute)]">{t("contact.subtitle")}</p>
      <div className="mt-8 grid gap-6 md:grid-cols-[1.3fr_1fr]">
        <div className="s-card px-6 py-2">
          {p.whatsapp && row("💬", "WhatsApp", <a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr" className="text-emerald-700 hover:underline">{p.whatsapp}</a>)}
          {phones.length > 0 && row("📞", t("identity.phone"), <div className="space-y-0.5">{phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr" className="block hover:underline">{x}</a>)}</div>)}
          {p.supportEmail && row("✉️", t("identity.email"), <a href={`mailto:${p.supportEmail}`} className="break-all hover:underline">{p.supportEmail}</a>)}
          {p.addressText && row("📍", t("identity.address"), <><p>{p.addressText}</p>{p.mapsUrl && <a href={p.mapsUrl} target="_blank" rel="noopener noreferrer" className="mt-1 inline-block text-sm font-extrabold" style={{ color: "var(--brand)" }}>{t("shop.openMap")} →</a>}</>)}
          {[["Facebook", p.facebookUrl], ["Instagram", p.instagramUrl], ["TikTok", p.tiktokUrl]].filter(([, u]) => u).length > 0 && row("🌐", t("contact.follow"), <div className="flex flex-wrap gap-3">{[["Facebook", p.facebookUrl], ["Instagram", p.instagramUrl], ["TikTok", p.tiktokUrl]].filter(([, u]) => u).map(([n, u]) => <a key={n} href={u} target="_blank" rel="noopener noreferrer" className="s-chip">{n}</a>)}</div>)}
        </div>
        <div className="s-card h-fit p-6"><h2 className="mb-3 text-lg font-extrabold">🕒 {t("identity.hours")}</h2>{p.workingHours && Object.keys(p.workingHours).length > 0 ? <HoursList hours={p.workingHours} t={t} /> : <p className="text-sm text-[var(--s-mute)]">{t("contact.noHours")}</p>}</div>
      </div>
      {p.about && <div className="s-card mt-6 p-6"><h2 className="mb-2 text-lg font-extrabold">{t("settings.about")}</h2><p className="whitespace-pre-line leading-relaxed text-[var(--s-mute)]">{p.about}</p></div>}
    </div>
  );
}
