# Caddy with the Cloudflare DNS plugin: one wildcard certificate (*.root) via DNS challenge instead of one certificate per tenant
# (Let's Encrypt allows ~50 new certificates per registered domain per week; 1000 tenants would hit that).
FROM caddy:2-builder AS build
RUN xcaddy build --with github.com/caddy-dns/cloudflare

FROM caddy:2-alpine
COPY --from=build /usr/bin/caddy /usr/bin/caddy
