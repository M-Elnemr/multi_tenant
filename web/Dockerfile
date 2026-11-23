# --- Dependencies ---
FROM mirror.gcr.io/library/node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# --- Build ---
FROM mirror.gcr.io/library/node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# The Google Fonts stylesheets come from font-mocks.cjs (node scripts/gen-font-mocks.mjs), not from Google at build time: on the CI runner Google answers
# some families with a font URL form Turbopack rejects. Only the font files are downloaded. Re-generate the file when a next/font/google declaration changes.
ENV NEXT_FONT_GOOGLE_MOCKED_RESPONSES=/app/font-mocks.cjs
# next/font/google downloads the fonts at build time and Google sometimes answers with a URL form Turbopack rejects ("queries have exactly one
# entry"); it differs per request, so a plain retry succeeds. The build only counts as done when the standalone server exists.
RUN npx next typegen && for i in 1 2 3 4 5; do npm run build && break; echo "next build failed (attempt $i), retrying"; sleep 5; done; test -f .next/standalone/server.js

# --- Runtime ---
FROM mirror.gcr.io/library/node:20-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup --system --gid 1001 nodejs && adduser --system --uid 1001 nextjs
COPY --from=build --chown=nextjs:nodejs /app/public* ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0
CMD ["node", "server.js"]
