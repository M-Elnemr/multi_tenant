import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  allowedDevOrigins: ["*.platform.localtest.me", "platform.localtest.me", "*.localhost"],   // local development on <shop>.platform.localtest.me only
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "X-Frame-Options", value: "SAMEORIGIN" },   // only the site itself may frame it (the template gallery previews the live site)
          // the parts of a content policy that cannot break a page: only this same site can frame us or inject a <base>/<object>, and forms only post to this site
          { key: "Content-Security-Policy", value: "frame-ancestors 'self'; base-uri 'self'; object-src 'none'; form-action 'self'" },
          // microphone=(self): the storefront chat records voice notes (the browser still asks the visitor once; with microphone=() it was blocked for the whole site)
          { key: "Permissions-Policy", value: "camera=(self), microphone=(self), geolocation=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
