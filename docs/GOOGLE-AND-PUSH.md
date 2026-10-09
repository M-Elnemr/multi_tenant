# Google sign-in (shop clients) and phone push (patients)

Both are optional and stay switched off until you add the keys below. Nothing breaks without them:
shop clients can still order as guests, and patients still see the queue refresh itself every 10 seconds.

## 1. Google sign-in for shop clients

1. Google Cloud Console -> create a project -> **APIs & Services -> OAuth consent screen** (External, app name "Elmanassa").
2. **Credentials -> Create credentials -> OAuth client ID -> Web application**.
   Authorized JavaScript origins: only `https://elmanassa.shop`. Shops do NOT need to be listed: a shop's "Continue with Google" sends the visitor
   to `https://elmanassa.shop/google`, which signs them in and hands the Google token back to the shop in the URL fragment
   (checked against a per-browser `state`, and only to real shops of this platform). The shop then creates its own session.
3. Copy the **Client ID** (it is public, not a secret) into the server `.env`:
   `GOOGLE_CLIENT_ID=1234567890-abc.apps.googleusercontent.com`, then `docker compose ... up -d`.
4. Mobile app (Elmanassa): create an additional **Android** OAuth client (package `com.elmanassa.app` + your signing SHA-1)
   and build with `--dart-define=GOOGLE_CLIENT_ID=<the Web client id>`.

## 2. Push notification when the doctor calls the patient (Firebase Cloud Messaging)

1. https://console.firebase.google.com -> add project -> add an **Android app** with package `com.elmanassa.app (iOS bundle id is the same)`.
2. Download `google-services.json` into `mobile/apps/elmanassa/android/app/` (the build applies the Google services plugin automatically when the file exists). Do not commit it if the repo is public.
3. Project settings -> **Service accounts -> Generate new private key**. Put the JSON on the server (for example `/opt/multitenant/secrets/fcm.json`, mode 600), the compose file mounts `./secrets` read-only at `/run/secrets` (create it: `mkdir -p secrets && chmod 700 secrets`)
   and set `FCM_CREDENTIALS_FILE=/run/secrets/fcm.json` in `.env`.
4. Rebuild the app. After sign-in it registers the phone; pressing **Call** in the clinic's waiting room sends the notification.
