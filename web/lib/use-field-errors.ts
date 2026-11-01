"use client";

import { useCallback, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { ApiError } from "./client";

/**
 * Field-level errors for a form: the server's `fields` map (and the form's own checks) are shown under each field,
 * and the page scrolls to the first one. A field is found by `data-field="<path>"` (Field's `name` prop sets it) or by an input's `name`.
 * Paths are the server's: "name", "variants[2].sku", "config.percent"...
 */
export function useFieldErrors() {
  const { t } = useI18n();
  const [errors, setErrors] = useState<Record<string, string>>({});

  /** The server writes English sentences; the Arabic for each lives under srv.<sentence> (or srv.<the part before ":" / a quoted value>). */
  const say = useCallback((msg: string) => {
    for (const k of [msg, msg.replace(/\s*[:"“].*$/, "").trim(), msg.replace(/\s+\S+$/, "").trim()]) {
      const key = `srv.${k}`;
      const s = t(key);
      if (s !== key) return s;
    }
    return msg;
  }, [t]);

  const scrollTo = useCallback((paths: string[]) => {
    if (typeof document === "undefined" || paths.length === 0) return;
    requestAnimationFrame(() => {
      for (const p of paths) {
        const el = document.querySelector<HTMLElement>(`[data-field="${CSS.escape(p)}"]`) ?? document.querySelector<HTMLElement>(`[name="${CSS.escape(p)}"]`)
          ?? document.querySelector<HTMLElement>(`[data-field^="${CSS.escape(p.replace(/\[.*$/, ""))}"]`);
        if (!el) continue;
        el.scrollIntoView({ behavior: "smooth", block: "center" });
        const input = el.matches("input,select,textarea") ? el : el.querySelector<HTMLElement>("input,select,textarea,button");
        setTimeout(() => input?.focus({ preventScroll: true }), 350);
        return;
      }
    });
  }, []);

  /** Shows the errors of a failed call; returns true when it had field errors (so the caller can skip the generic alert). */
  const fromError = useCallback((e: unknown) => {
    const f = (e as ApiError | null)?.fields;
    if (!f || Object.keys(f).length === 0) { setErrors({}); return false; }
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(f)) out[k] = say(v);
    setErrors(out);
    scrollTo(Object.keys(out));
    return true;
  }, [say, scrollTo]);

  /** The form's own checks before sending: { field: message }. Returns true when the form is fine. */
  const check = useCallback((local: Record<string, string | false | null | undefined>) => {
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(local)) if (v) out[k] = v;
    setErrors(out);
    scrollTo(Object.keys(out));
    return Object.keys(out).length === 0;
  }, [scrollTo]);

  const err = useCallback((path: string) => errors[path], [errors]);
  /** Any error under this prefix (a whole row: "variants[2]"). */
  const errUnder = useCallback((prefix: string) => Object.entries(errors).find(([k]) => k === prefix || k.startsWith(prefix + ".") || k.startsWith(prefix + "["))?.[1], [errors]);
  const clear = useCallback((path?: string) => setErrors((prev) => { if (!path) return {}; const n = { ...prev }; delete n[path]; return n; }), []);
  return { errors, err, errUnder, fromError, check, clear, any: Object.keys(errors).length > 0 };
}
