# Snapchat — notes from the OFFICIAL docs (developers.snap.com, read 2026-11-20)

## Customer direct messages: NOT available
- "Messaging" (Public Profile API, /marketing-api/Public-Profile-API/Messaging): "The messaging API allows brands to communicate directly with creators." Behind a separate allowlist (request through your Snap point of contact). Endpoints on businessapi.snapchat.com: GET .../public_profiles/{profile_id}/group_conversation (needs creator_profile_id), POST .../group_conversation_messages (TEXT only, one message per call, "collaboration message"), GET .../group_conversation_messages, GET .../group_conversation_messages_count. This is brand<->creator partnership messaging, NOT customer support/DM. No webhook for incoming messages is documented. No official business customer-chat API found anywhere in the Marketing / Public Profile docs.

## Public Profile API (allowlist only)
- Two functions: Creator Discovery and Content Management (post Stories / Spotlights on behalf of brands or creators, stats, promote with paid media). "The Public Profile API is currently allowlist only": create an OAuth app, send ONLY the client id (never the secret) to the Snap contact. Needs a Business Account (Organization) + OAuth app. Scope: snapchat-profile-api. Media upload: encrypted file + container (POST businessapi.snapchat.com/v1/public_profiles/{id}/media) etc.

## Marketing API (general)
- OAuth app in Snap Business Manager (Organization Admin) -> client_id / client_secret (secret shown once) + redirect_uri.
- GET https://accounts.snapchat.com/login/oauth2/authorize?client_id&redirect_uri&response_type=code&scope=snapchat-marketing-api&state (scopes: snapchat-marketing-api, snapchat-offline-conversions-api, snapchat-profile-api).
- POST https://accounts.snapchat.com/login/oauth2/access_token (form): grant_type=authorization_code, client_id, client_secret, code, redirect_uri -> {access_token (3600 s), refresh_token, expires_in, token_type}. Refresh: grant_type=refresh_token, refresh_token, client_id, client_secret. A 401 "access token expired" => refresh and retry.
- GET https://adsapi.snapchat.com/v1/me/organizations (?with_ad_accounts=true) lists organizations (id, name, roles...).

## Catalogs / product feeds (official: Dynamic Product Ads Guide)
- Catalog: POST https://adsapi.snapchat.com/v1/organizations/{organization_id}/catalogs body {"catalogs":[{"organization_id","name" (<=375),"vertical":"COMMERCE"}]} -> catalogs[].catalog.id. "Organization Admin" is required to create/update/delete a catalog. Max 200 catalogs/organization. GET /v1/organizations/{id}/catalogs, GET /v1/catalogs/{id}, DELETE /v1/catalogs/{id}.
- Product feed (1:1 with a catalog): POST https://adsapi.snapchat.com/v1/catalogs/{catalog_id}/product_feeds body {"product_feeds":[{"catalog_id","name","default_currency" (ISO 4217, EGP/SAR/AED supported),"status":"ACTIVE","schedule":{"url","interval_type":"HOURLY|DAILY|WEEKLY|MONTHLY","timezone","hour","minute","interval_count"}}]}. Snap fetches the URL on the schedule. GET /v1/catalogs/{id}/product_feeds; GET /v1/product_feeds/{id}/feed_uploads (status INITIALIZED|FETCHING|PROCESSING|COMPLETE|ERRORED, summary{total_items, items_modified, items_errored, items_with_warnings, issues_summary}). A feed with a schedule cannot also get manual feed uploads.
- Feed file formats (businesshelp "Catalog Formatting and Specifications"): HTTPS/HTTP/SFTP/FTP: CSV, TSV, TXT, XML (RSS/ATOM); up to 10,000,000 products; feeds used on other platforms are accepted if they follow Snap's field specs; URL parameters must be on links; refresh hourly or daily.
- Catalog `source` values: BUSINESS_MANAGER, FEED, WOOCOMMERCE, SHOPIFY.
- Dynamic Product Ads (campaign objective SALES, product_properties.catalog_id, ad squad product_set_id, creative render_type DYNAMIC) need a catalog; building ads is a separate (not built) capability.

## Not read / unverified
- The exact required product-feed fields of Snap's ecommerce spec (the businesshelp "Ecommerce" field list was not opened); our existing /feeds/google.xml carries id, title, description, link, image_link, price, availability, brand, condition.
- Rate limits; costs (none found for catalog/feed in the docs read; ad spend is separate).
