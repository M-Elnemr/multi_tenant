"use client";

import { useRouter } from "next/navigation";
import { defaultHours, type WeekHours } from "@/lib/hours";
import { BannersCard } from "../dashboard/banners";
import { HoursEditor } from "../dashboard/hours-editor";
import { BranchesCard } from "../dashboard/store-identity";
import { useI18n } from "../i18n-provider";
import { EmailInput, PhoneInput } from "../inputs";
import { Button, Field, Input } from "../ui";
import { DrawerShell } from "./drawer-shell";
import { Group, OpenStatusField, PhotoField, TextField, validUrl } from "./fields";
import { useSectionForm } from "./use-section-form";
import type { EditorProps } from "./clinic-editors";

type S = {
  storeName: string; shortDescription: string; coverFileId: string | null; whatsapp: string; supportPhone: string; supportEmail: string; addressText: string; mapsUrl: string;
  extraPhones: string[]; facebookUrl: string; instagramUrl: string; tiktokUrl: string; websiteUrl: string; workingHours: WeekHours;
  announcement: string; isOpen: boolean; closedMessage: string; trustTiles: { title: string; text: string }[];
};
const PATH = "store/profile";
const toBody = (c: Partial<S>): Record<string, unknown> => ({ ...c, ...("coverFileId" in c ? { coverFileId: c.coverFileId ?? "" } : {}) });
const list = (s: string) => s.split(/[,،]/).map((x) => x.trim()).filter(Boolean);

function useStoreForm(keys: readonly (keyof S & string)[], validate?: (v: Partial<S>) => Record<string, string>) {
  const router = useRouter();
  return useSectionForm<S>(PATH, keys, { toBody, validate, onSaved: () => router.refresh() });
}
const urlErr = (t: (k: string) => string, ...pairs: [string, string | undefined][]) => Object.fromEntries(pairs.filter(([, v]) => !validUrl(v)).map(([k]) => [k, t("se.badUrl")]));

/** Banners (their own list) and the main banner shown when there are none: store name, short description and cover photo. */
export function BannersEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useStoreForm(["storeName", "shortDescription", "coverFileId"], (v): Record<string, string> => (v.storeName?.trim() ? {} : { storeName: t("se.required") }));
  return (
    <DrawerShell icon="tag" title={t("site.section.banners")} description={t("se.d.banners")} form={f} onClose={onClose} wide>
      <Group title={t("se.bannerList")} hint={t("banners.hint")}><BannersCard /></Group>
      <Group title={t("se.mainBanner")} hint={t("se.mainBannerHint")}>
        <TextField label={t("se.storeName")} value={f.v.storeName} max={120} error={f.errors.storeName} onChange={(v) => f.set({ storeName: v })} />
        <TextField label={t("se.shortDescription")} value={f.v.shortDescription} max={300} onChange={(v) => f.set({ shortDescription: v })} />
        <PhotoField label={t("identity.cover")} hint={t("identity.coverHint")} value={f.v.coverFileId} onChange={(id) => f.set({ coverFileId: id })} />
      </Group>
    </DrawerShell>
  );
}

export function TrustEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useStoreForm(["trustTiles"]);
  const tiles = f.v.trustTiles ?? [];
  const upd = (i: number, patch: Partial<{ title: string; text: string }>) => f.set({ trustTiles: tiles.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
  return (
    <DrawerShell icon="award" title={t("site.section.trust")} description={t("se.d.trust")} form={f} onClose={onClose}>
      <p className="rounded-2xl bg-slate-50 p-3.5 text-sm text-slate-600">{tiles.length === 0 ? t("se.trustDefault") : t("site.tilesHint")}</p>
      {tiles.map((x, i) => (
        <div key={i} className="space-y-2 rounded-2xl border border-slate-200 bg-white p-3.5 shadow-sm">
          <div className="flex items-center justify-between"><span className="text-xs font-bold text-slate-400">#{i + 1}</span><button type="button" onClick={() => f.set({ trustTiles: tiles.filter((_, j) => j !== i) })} className="text-xs font-semibold text-red-600 hover:underline">{t("common.remove")}</button></div>
          <Input value={x.title} maxLength={60} placeholder={t("site.tileTitle")} onChange={(e) => upd(i, { title: e.target.value })} />
          <Input value={x.text} maxLength={160} placeholder={t("site.tileText")} onChange={(e) => upd(i, { text: e.target.value })} />
        </div>
      ))}
      {tiles.length < 6 && <Button type="button" variant="secondary" onClick={() => f.set({ trustTiles: [...tiles, { title: "", text: "" }] })}>+ {t("site.addTile")}</Button>}
    </DrawerShell>
  );
}

export function WhatsAppEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useStoreForm(["whatsapp", "supportPhone"]);
  return (
    <DrawerShell icon="chat" title={t("site.section.whatsapp")} description={t("se.d.whatsapp")} form={f} onClose={onClose}>
      <Field label="WhatsApp" hint={t("se.whatsappBandHint")}><PhoneInput value={f.v.whatsapp ?? ""} onValue={(whatsapp) => f.set({ whatsapp })} /></Field>
      <Field label={t("identity.phone")}><PhoneInput value={f.v.supportPhone ?? ""} onValue={(supportPhone) => f.set({ supportPhone })} /></Field>
    </DrawerShell>
  );
}

export function StoreBranchesEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  return <DrawerShell icon="pin" title={t("site.section.branches")} description={t("se.d.branches")} onClose={onClose} wide><BranchesCard /></DrawerShell>;
}

/** Site-wide: announcement bar and open / closed. */
export function StoreAnnounceEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useStoreForm(["announcement", "isOpen", "closedMessage"]);
  return (
    <DrawerShell icon="bell" title={t("se.announce")} description={t("se.d.announceStore")} form={f} onClose={onClose}>
      <TextField label={t("identity.announcement")} hint={t("identity.announcementHint")} value={f.v.announcement} max={300} onChange={(v) => f.set({ announcement: v })} />
      <OpenStatusField isOpen={f.v.isOpen} message={f.v.closedMessage} onChange={(p) => f.set(p)} />
    </DrawerShell>
  );
}

/** Site-wide: the footer's contact details, opening hours and social links. */
export function StoreContactEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useStoreForm(["supportPhone", "supportEmail", "addressText", "mapsUrl", "extraPhones", "facebookUrl", "instagramUrl", "tiktokUrl", "websiteUrl", "workingHours"],
    (v) => urlErr(t, ["mapsUrl", v.mapsUrl], ["facebookUrl", v.facebookUrl], ["instagramUrl", v.instagramUrl], ["tiktokUrl", v.tiktokUrl], ["websiteUrl", v.websiteUrl]));
  return (
    <DrawerShell icon="phone" title={t("se.footer")} description={t("se.d.footer")} form={f} onClose={onClose} wide>
      <Group title={t("se.reach")}>
        <Field label={t("identity.phone")}><PhoneInput value={f.v.supportPhone ?? ""} onValue={(supportPhone) => f.set({ supportPhone })} /></Field>
        <Field label={t("identity.email")}><EmailInput value={f.v.supportEmail ?? ""} onValue={(supportEmail) => f.set({ supportEmail })} /></Field>
        <Field label={t("identity.extraPhones")} hint={t("identity.extraPhonesHint")}><Input dir="ltr" value={(f.v.extraPhones ?? []).join(", ")} onChange={(e) => f.set({ extraPhones: list(e.target.value) })} /></Field>
        <TextField label={t("identity.address")} value={f.v.addressText} max={300} onChange={(v) => f.set({ addressText: v })} />
        <TextField label={t("identity.mapsUrl")} hint={t("identity.mapsHint")} ltr value={f.v.mapsUrl} max={500} error={f.errors.mapsUrl} onChange={(v) => f.set({ mapsUrl: v })} />
      </Group>
      <Group title={t("se.social")}>
        <Field label="Facebook" error={f.errors.facebookUrl}><Input dir="ltr" placeholder="https://facebook.com/…" value={f.v.facebookUrl ?? ""} onChange={(e) => f.set({ facebookUrl: e.target.value })} /></Field>
        <Field label="Instagram" error={f.errors.instagramUrl}><Input dir="ltr" placeholder="https://instagram.com/…" value={f.v.instagramUrl ?? ""} onChange={(e) => f.set({ instagramUrl: e.target.value })} /></Field>
        <Field label="TikTok" error={f.errors.tiktokUrl}><Input dir="ltr" placeholder="https://tiktok.com/@…" value={f.v.tiktokUrl ?? ""} onChange={(e) => f.set({ tiktokUrl: e.target.value })} /></Field>
        <Field label={t("identity.website")} error={f.errors.websiteUrl}><Input dir="ltr" placeholder="https://…" value={f.v.websiteUrl ?? ""} onChange={(e) => f.set({ websiteUrl: e.target.value })} /></Field>
      </Group>
      <Group title={t("identity.hours")}>
        <HoursEditor value={f.v.workingHours ?? {}} onChange={(workingHours) => f.set({ workingHours })} />
        <Button size="sm" variant="ghost" onClick={() => f.set({ workingHours: defaultHours() })}>{t("identity.hoursDefault")}</Button>
      </Group>
    </DrawerShell>
  );
}
