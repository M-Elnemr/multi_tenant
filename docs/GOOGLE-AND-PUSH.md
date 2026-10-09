# Google sign-in (shop clients) and phone push (patients)

Both are optional and stay switched off until you add the keys below. Nothing breaks without them:
shop clients can still order as guests, and patients still see the queue refresh itself every 10 seconds.

## 1. Google sign-in for shop clients

1. Google Cloud Console -> create a project -> **APIs & Services -> OAuth consent screen** (External, app name "Elmanassa").
2. **Credentials -> Create credentials -> OAuth client ID -> Web application**.
   Authorized JavaScript origins: `https://elmanassa.shop` plus every shop address that should show the button
   (Google needs each origin listed; a wildcard `*.elmanassa.shop` is not allowed - add shops as they are created, or use one shared sign-in page later).
3. Copy the **Client ID** (it is public, not a secret) into the server `.env`:
   `GOOGLE_CLIENT_ID=1234567890-abc.apps.googleusercontent.com`, then `docker compose ... up -d`.
4. Mobile customer app: create an additional **Android** OAuth client (package `com.platform.platform_customer` + your signing SHA-1)
   and build with `--dart-define=GOOGLE_CLIENT_ID=<the Web client id>`.

## 2. Push notification when the doctor calls the patient (Firebase Cloud Messaging)

1. https://console.firebase.google.com -> add project -> add an **Android app** with package `com.platform.platform_patient`.
2. Download `google-services.json` into `mobile/apps/patient/android/app/` (the build applies the Google services plugin automatically when the file exists). Do not commit it if the repo is public.
3. Project settings -> **Service accounts -> Generate new private key**. Put the JSON on the server (for example `/opt/multitenant/secrets/fcm.json`, mode 600), mount it into the backend container
   and set `FCM_CREDENTIALS_FILE=/run/secrets/fcm.json` in `.env`.
4. Rebuild the patient app. After sign-in it registers the phone; pressing **Call** in the clinic's waiting room sends the notification.
