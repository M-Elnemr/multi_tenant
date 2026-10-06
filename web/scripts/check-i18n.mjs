// Fails when a t("key") used in the source is missing from either language, or the two dictionaries drift apart.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const src = readFileSync(join(root, "lib/messages.ts"), "utf8");
const grab = (name) => {
  const start = src.indexOf(`const ${name}`);
  const body = src.slice(start, src.indexOf("\n};", start));
  return new Set([...body.matchAll(/^\s*"([^"]+)":/gm)].map((m) => m[1]));
};
const en = grab("en");
const ar = grab("ar");

const used = new Set();
const dynamicPrefixes = new Set();
const literals = [];
const walk = (dir) => {
  for (const f of readdirSync(dir)) {
    if (["node_modules", ".next", "scripts"].includes(f)) continue;
    const p = join(dir, f);
    if (statSync(p).isDirectory()) walk(p);
    else if (/\.(tsx?|mjs)$/.test(f) && !p.endsWith("messages.ts")) {
      const text = readFileSync(p, "utf8");
      for (const m of text.matchAll(/\bt\(\s*"([^"]+)"/g)) used.add(m[1]);
      for (const m of text.matchAll(/\bt\(\s*`([^`$]+)\$\{/g)) dynamicPrefixes.add(m[1]);
      literals.push(text);
    }
  }
};
walk(root);

// Keys passed around as plain strings (nav labels, copy tables): any "ns.key" literal whose namespace we define must exist.
const namespaces = new Set([...en].map((k) => k.split(".")[0]));
const IGNORE = new Set(["next.js", "react.js"]);
const PERMISSION = /\.(manage|read|update|create|delete|adjust|moderate|upload|review|export|update_status)$/;
for (const text of literals) {
  for (const m of text.matchAll(/["'`]([a-z][A-Za-z]*)\.([A-Za-z0-9_]+)["'`]/g)) {
    const key = `${m[1]}.${m[2]}`;
    if (namespaces.has(m[1]) && !PERMISSION.test(key) && !IGNORE.has(key) && !en.has(key) && !/^(status|role|feature|pay|ship|weekday|exception|inventory|orders|clinicSettings|error)\./.test(key) && !used.has(key)) used.add(key);
  }
}

const problems = [];
for (const k of used) {
  if (!en.has(k)) problems.push(`missing in en: ${k}`);
  if (!ar.has(k)) problems.push(`missing in ar: ${k}`);
}
for (const k of en) if (!ar.has(k)) problems.push(`en-only key (no Arabic): ${k}`);
for (const k of ar) if (!en.has(k)) problems.push(`ar-only key (no English): ${k}`);
for (const prefix of dynamicPrefixes) if (![...en].some((k) => k.startsWith(prefix))) problems.push(`no keys under dynamic prefix: ${prefix}`);

if (problems.length) {
  console.error(problems.join("\n"));
  process.exit(1);
}
console.log(`i18n ok: ${used.size} keys used, ${en.size} defined in each language`);
