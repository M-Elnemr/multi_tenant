"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";

/** "Where is my booking?": takes a booking number to the manage page (which then asks for the phone and looks it up for real). */
export function TrackBox() {
  const { t } = useI18n();
  const router = useRouter();
  const [n, setN] = useState("");
  return (
    <form className="rp-track" onSubmit={(e) => { e.preventDefault(); router.push(`/book/manage${n.trim() ? `?n=${encodeURIComponent(n.trim())}` : ""}`); }}>
      <p className="rp-label">{t("vn.rp.track")}</p>
      <label className="rp-track-row"><span className="rp-sr">{t("vn.manage.number")}</span>
        <input value={n} dir="ltr" placeholder={t("vn.manage.number")} onChange={(e) => setN(e.target.value)} />
        <button className="rp-track-go" aria-label={t("vn.manage.find")}><Icon name="arrow" className="h-5 w-5 flip-rtl" /></button>
      </label>
      <p className="rp-track-hint">{t("vn.rp.trackHint")}</p>
    </form>
  );
}
