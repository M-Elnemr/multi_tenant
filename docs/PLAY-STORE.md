# Publishing Tabib (patients) and Elmanassa Business on Google Play

| | Client app | Business app |
|---|---|---|
| Folder | `mobile/apps/elmanassa` | `mobile/apps/provider` |
| Package | `com.elmanassa.app` | `com.elmanassa.business` |
| Name | Tabib - طبيب | Elmanassa Business |
| Version | 1.3.3 (code 13) | 1.3.6 (code 19) |

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
**Tabib (ar)** — Title: طبيب · Short: تابع دورك في العيادة وسجلك الطبي وروشتاتك وتحاليلك من تطبيق واحد.
**Tabib (en)** — Title: Tabib · Short: Follow your place in the clinic queue and keep your visits, prescriptions and test results in one app.

**Tabib full description (ar)** — every line below is a feature the app has today (no shopping, no online booking, no online payment):
```
طبيب هو تطبيق المريض لمتابعة زيارتك للعيادة وحفظ ملفك الطبي في مكان واحد، بدون انتظار مجهول ولا أوراق ضائعة.

⏱️ تابع دورك في العيادة
• شاهد دورك في الانتظار لحظة بلحظة: رقمك وعدد المرضى الذين قبلك.
• يصلك إشعار على هاتفك عندما يحين دورك أو عندما تتقدم في الدور.
• تابع دور أطفالك المسجّلين لدى العيادة من نفس الحساب.
• اعرف عدد المنتظرين الآن وحالة العيادة (مفتوحة أو مغلقة مؤقتًا).

📋 ملفك الطبي في مكان واحد
• راجع زياراتك السابقة وتواريخها والطبيب والخدمة.
• اطّلع على الروشتات والملاحظات الطبية التي أتاحها لك الطبيب.
• راجع طلبات التحاليل والأشعة ونتائجها، ورقم الحالة: قيد مراجعة الطبيب أو تمت المراجعة.
• ارفع نتيجة التحليل المطلوبة بالكاميرا أو من المعرض لتصل إلى طبيبك.

🏥 كل ما تحتاجه عن العيادة
• العنوان والاتجاهات على الخريطة، واتصال مباشر أو رسالة واتساب.
• مواعيد العمل، والخدمات وأسعارها، والأطباء وخبراتهم وسعر الكشف.
• شركات التأمين المقبولة والأسئلة الشائعة.

🪪 رقم المريض ورمز QR
• احصل على رقم مريض خاص بك ورمز QR، لتضيفك أي عيادة تتعامل مع المنصة بسرعة دون تكرار بياناتك.
• يمكنك أيضًا تسجيل الدخول برقم المريض أو برقم الهاتف.

⚙️ حسابك
• تغيير كلمة المرور في أي وقت.
• التطبيق بالعربية والإنجليزية.
• يمكنك مغادرة العيادة من حسابك متى شئت.

ملاحظة: حساب المريض تنشئه العيادة لك عند أول زيارة، وتعطيك رقم هاتفك وكلمة مرور مؤقتة لتبدأ.

طبيب — دورك، وملفك، وروشتاتك في جيبك.
```
**Tabib full description (en)**
```
Tabib is the patient app for following your clinic visit and keeping your medical file in one place.

Follow your place in the queue
- See your number and how many patients are ahead of you, live.
- Get a notification when it is your turn or when you move up.
- Follow your children's turn from the same account.
- See how many people are waiting and whether the clinic is open.

Your medical file
- Review your past visits with the doctor and service.
- See the prescriptions and notes your doctor shared with you.
- Review requested lab and radiology tests and their status, and upload a requested result with your camera or gallery.

About the clinic
- Address, directions, one-tap call or WhatsApp, opening hours, services and prices, doctors, accepted insurance and FAQs.

Your patient ID and QR code
- A personal patient ID and QR code so any clinic on the platform can add you quickly. Sign in with your ID or phone number.

Account: change your password, switch between Arabic and English, leave a clinic anytime.
Note: your clinic creates your account at your first visit and gives you a temporary password.
```
**Business (ar)** — Title: المنصّة للأعمال · Short: تابع طلبات متجرك ومواعيد عيادتك وإحصاءاتك من هاتفك.
**Business (en)** — Title: Elmanassa Business · Short: Follow your shop orders, clinic queue and daily stats from your phone.
