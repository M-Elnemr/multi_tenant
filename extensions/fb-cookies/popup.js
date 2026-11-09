// Reads the Facebook session cookies of this browser and copies them as one line.
// Nothing leaves the browser: the result goes to the clipboard only.
const NEEDED = ["c_user", "xs"];
const WANTED = ["c_user", "xs", "datr", "fr", "sb", "wd", "i_user"];

const msg = document.getElementById("msg");
const btn = document.getElementById("go");

function show(text, ok) {
  msg.textContent = text;
  msg.className = ok ? "ok" : "bad";
}

btn.addEventListener("click", async () => {
  btn.disabled = true;
  try {
    const all = await chrome.cookies.getAll({ domain: "facebook.com" });
    const byName = new Map();
    for (const c of all) if (!byName.has(c.name)) byName.set(c.name, c.value);
    const missing = NEEDED.filter((n) => !byName.get(n));
    if (missing.length) {
      show("مش مسجّل دخول في فيسبوك على المتصفح ده. / Not logged in to Facebook in this browser.", false);
      return;
    }
    // i_user (set by Facebook when you are switched into a Page) is the id the server acts as.
    const line = WANTED.filter((n) => byName.get(n)).map((n) => `${n}=${byName.get(n)}`).join("; ") + ";";
    await navigator.clipboard.writeText(line);
    show(`اتنسخت ✓ (حساب ${byName.get("c_user")}). الصقها في كارت ماسنجر. / Copied. Paste it into the Messenger card.`, true);
  } catch (e) {
    show("حصل خطأ: " + (e && e.message ? e.message : e), false);
  } finally {
    btn.disabled = false;
  }
});
