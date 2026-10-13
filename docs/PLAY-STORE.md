# Publishing Tabib (patients) and Elmanassa Business on Google Play

| | Client app | Business app |
|---|---|---|
| Folder | `mobile/apps/elmanassa` | `mobile/apps/provider` |
| Package | `com.elmanassa.app` | `com.elmanassa.business` |
| Name | Tabib - طبيب | Elmanassa Business |
| Version | 1.2.0 (code 8) | 1.2.0 (code 12) |

## Build (signed)
Each app needs `android/key.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` of its **upload keystore**
(kept outside the repo, e.g. `~/elmanassa-release-keys/`). Without it a release build fails on purpose (no debug-signed uploads).

```
cd mobile/apps/elmanassa && flutter build appbundle --release   # build/app/outputs/bundle/release/app-release.aab
cd mobile/apps/provider  && flutter build appbundle --release
```
Raise `version:` in `pubspec.yaml` (the number after `+`) for every upload.

## Play Console steps (owner)
1. Create the two apps; keep **Play App Signing** on; upload each `.aab` to **Internal testing** first.
2. **Google sign-in (client app)**: Google Cloud → Credentials → the Android OAuth client for `com.elmanassa.app`: add the upload-key SHA-1
   now and the *App signing key* SHA-1 (Play Console → Test and release → App integrity) after the first upload.
3. Store listing: privacy policy `https://elmanassa.shop/privacy`; account deletion URL `https://elmanassa.shop/delete-account`; support email developer.elnemr@gmail.com.
4. Forms: Data safety (name, phone, address, email, device token for notifications; encrypted in transit; deletable on request; no ads; no sale),
   Content rating, Target audience (18+), **Health apps declaration** (clinic appointments; not a medical-device/diagnosis app), Ads: none.
5. Reviewer access: Tabib (patient app) — sign in with the demo patient's mobile number and password given in the review notes (never in this repo); Business app — shop owner phone `01001234567` (password given in the review notes, never in this repo).
6. Screenshots (phone, min 2) from a real device, then promote to production.

## Listing text
**Tabib (ar)** — Title: طبيب · Short: احجز عند طبيبك وتابع دورك وسجلك الطبي والروشتات من تطبيق واحد.
**Tabib (en)** — Title: Tabib · Short: Book your doctor, follow your place in the queue and keep your records and prescriptions in one app.
(The shop side of the app is kept in the code, hidden with `SHOP_ENABLED=false`; build with `--dart-define=SHOP_ENABLED=true` to bring it back.)
**Business (ar)** — Title: المنصّة للأعمال · Short: تابع طلبات متجرك ومواعيد عيادتك وإحصاءاتك من هاتفك.
**Business (en)** — Title: Elmanassa Business · Short: Follow your shop orders, clinic queue and daily stats from your phone.
