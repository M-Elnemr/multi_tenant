"use client";

import Script from "next/script";

type W = { fbq?: (...a: unknown[]) => void; ttq?: { track: (...a: unknown[]) => void }; gtag?: (...a: unknown[]) => void };

/** Ad and analytics tags. Only the IDs the shop owner entered are loaded (validated server-side); nothing is loaded for shops that did not set any. */
export function MarketingTags({ metaPixelId, tiktokPixelId, gaId }: { metaPixelId?: string; tiktokPixelId?: string; gaId?: string }) {
  return (
    <>
      {metaPixelId && (
        <Script id="meta-pixel" strategy="afterInteractive">{`!function(f,b,e,v,n,t,s){if(f.fbq)return;n=f.fbq=function(){n.callMethod?n.callMethod.apply(n,arguments):n.queue.push(arguments)};if(!f._fbq)f._fbq=n;n.push=n;n.loaded=!0;n.version='2.0';n.queue=[];t=b.createElement(e);t.async=!0;t.src=v;s=b.getElementsByTagName(e)[0];s.parentNode.insertBefore(t,s)}(window,document,'script','https://connect.facebook.net/en_US/fbevents.js');fbq('init','${metaPixelId}');fbq('track','PageView');`}</Script>
      )}
      {tiktokPixelId && (
        <Script id="tiktok-pixel" strategy="afterInteractive">{`!function(w,d,t){w.TiktokAnalyticsObject=t;var ttq=w[t]=w[t]||[];ttq.methods=["page","track","identify","instances","debug","on","off","once","ready","alias","group","enableCookie","disableCookie"],ttq.setAndDefer=function(t,e){t[e]=function(){t.push([e].concat(Array.prototype.slice.call(arguments,0)))}};for(var i=0;i<ttq.methods.length;i++)ttq.setAndDefer(ttq,ttq.methods[i]);ttq.load=function(e){var n="https://analytics.tiktok.com/i18n/pixel/events.js";ttq._i=ttq._i||{},ttq._i[e]=[],ttq._i[e]._u=n,ttq._t=ttq._t||{},ttq._t[e]=+new Date,ttq._o=ttq._o||{},ttq._o[e]={};var o=document.createElement("script");o.type="text/javascript",o.async=!0,o.src=n+"?sdkid="+e+"&lib="+t;var a=document.getElementsByTagName("script")[0];a.parentNode.insertBefore(o,a)};ttq.load('${tiktokPixelId}');ttq.page()}(window,document,'ttq');`}</Script>
      )}
      {gaId && (
        <>
          <Script src={`https://www.googletagmanager.com/gtag/js?id=${gaId}`} strategy="afterInteractive" />
          <Script id="ga" strategy="afterInteractive">{`window.dataLayer=window.dataLayer||[];function gtag(){dataLayer.push(arguments)}gtag('js',new Date());gtag('config','${gaId}');`}</Script>
        </>
      )}
    </>
  );
}

/** Report a completed order to whichever tags are loaded (value in major units). */
export function trackPurchase(value: number, currency: string) {
  const w = window as unknown as W;
  try { w.fbq?.("track", "Purchase", { value, currency }); } catch { /* tag blocked */ }
  try { w.ttq?.track("CompletePayment", { value, currency }); } catch { /* tag blocked */ }
  try { w.gtag?.("event", "purchase", { value, currency }); } catch { /* tag blocked */ }
}
