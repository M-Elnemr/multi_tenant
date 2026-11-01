import { Suspense } from "react";
import { getT } from "@/lib/i18n-server";
import { TrackBooking } from "./track";

export default async function BookingTrack() {
  const { t } = await getT();
  return (
    <section className="lw-section">
      <div className="lw-container max-w-xl">
        <p className="lw-eyebrow">{t("lws.trackEyebrow")}</p>
        <h1 className="lw-title mt-3">{t("lws.trackTitle")}</h1>
        <Suspense><TrackBooking /></Suspense>
      </div>
    </section>
  );
}
