# Elmanassa Facebook connect helper (Chrome extension)

Copies the Facebook session cookies (`c_user`, `xs`, `datr`, `fr`, `sb`, `wd`) of the browser you are logged in with, as one line, to the clipboard. Paste it into the Messenger card in the dashboard (استهداف العملاء الحاليين). Nothing is sent anywhere by the extension.

Install: `chrome://extensions` → Developer mode → Load unpacked → pick this folder (or unzip `elmanassa-fb-cookies.zip` first). The manifest key keeps the extension id stable (`cklfomaklgpbophhhfngopphmomanmpa`) so the dashboard can find it. In the dashboard, the Messenger card has a one-click Connect button that asks this extension for the cookies.
The cookies give full access to that Facebook account. Do not log out in that browser afterwards (logging out ends the session).
