// Merge web/i18n-additions/ws-*.json into web/lib/messages.ts (lead-only; idempotent: keys already present are skipped).
// Accepts {"key": [en, ar]} and {"en": {...}, "ar": {...}}. Run from repo root: node .mission/design-revolution/merge-i18n.mjs
import fs from "node:fs";
import path from "node:path";

const dir = "web/i18n-additions";
const target = "web/lib/messages.ts";
const files = fs.readdirSync(dir).filter((f) => /^ws-.*\.json$/.test(f)).sort();
let src = fs.readFileSync(target, "utf8");

const enEnd = src.indexOf("\n};\n\nconst ar: Record<string, string> = {");
const arEnd = src.indexOf("\n};\n\nexport const dictionaries");
if (enEnd < 0 || arEnd < 0) throw new Error("messages.ts layout not recognised");

const have = (k) => src.includes(JSON.stringify(k) + ":");
const add = { en: [], ar: [] };
const seen = new Set();
for (const f of files) {
  const j = JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));
  const pairs = {};
  if (j.en && j.ar && typeof j.en === "object") for (const k of Object.keys(j.en)) pairs[k] = [j.en[k], j.ar[k] ?? j.en[k]];
  else for (const [k, v] of Object.entries(j)) if (Array.isArray(v)) pairs[k] = v;
  for (const [k, [en, ar]] of Object.entries(pairs)) {
    if (have(k) || seen.has(k)) continue;
    seen.add(k);
    add.en.push(`  ${JSON.stringify(k)}: ${JSON.stringify(en)},`);
    add.ar.push(`  ${JSON.stringify(k)}: ${JSON.stringify(ar)},`);
  }
}
// insert ar first (later in file) so the en offset stays valid
src = src.slice(0, arEnd) + (add.ar.length ? "\n" + add.ar.join("\n") : "") + src.slice(arEnd);
src = src.slice(0, enEnd) + (add.en.length ? "\n" + add.en.join("\n") : "") + src.slice(enEnd);
fs.writeFileSync(target, src);
console.log(`merged ${add.en.length} keys from ${files.length} files`);
