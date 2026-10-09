import { HoursList } from "@/components/shop/hours";
import type { ShopBranch } from "@/components/shop/types";
import { backendJson } from "@/lib/backend";
import { governorateName } from "@/lib/governorates";
import { getT } from "@/lib/i18n-server";
import { whatsappLink } from "@/lib/whatsapp";

export default async function Branches() {
  const { t, locale } = await getT();
  const branches = await backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]);
  return (
    <div>
      <p className="s-eyebrow">{t("shop.visitUs")}</p>
      <h1 className="mt-1 text-3xl font-extrabold sm:text-4xl">{t("shop.branches")}</h1>
      {branches.length === 0 ? <p className="s-card mt-8 p-10 text-center text-[var(--s-mute)]">{t("branches.none")}</p> : (
        <div className="mt-8 grid gap-5 md:grid-cols-2">
          {branches.map((b) => (
            <article key={b.id} className="s-card space-y-3 p-6">
              <div className="flex items-start justify-between gap-3"><h2 className="text-xl font-extrabold">{b.name}</h2>{b.isPickup && <span className="s-badge">{t("identity.pickup")}</span>}</div>
              <p className="text-[var(--s-mute)]">📍 {[b.addressLine1, b.area, b.landmark, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</p>
              <div className="flex flex-wrap gap-2">
                {b.phone && <a href={`tel:${b.phone}`} dir="ltr" className="s-chip">📞 {b.phone}</a>}
                {b.whatsapp && <a href={whatsappLink(b.whatsapp)} target="_blank" rel="noopener noreferrer" className="s-chip">💬 WhatsApp</a>}
                {b.mapsUrl && <a href={b.mapsUrl} target="_blank" rel="noopener noreferrer" className="s-chip">🗺️ {t("shop.openMap")}</a>}
              </div>
              {b.workingHours && Object.keys(b.workingHours).length > 0 && <details className="text-sm"><summary className="cursor-pointer font-extrabold">🕒 {t("identity.hours")}</summary><HoursList hours={b.workingHours} t={t} className="mt-3 text-[var(--s-mute)]" /></details>}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
