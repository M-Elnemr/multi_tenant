"use client";

import { useEffect, useId, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useI18n } from "../i18n-provider";

export type Found = { variantId: string; name: string; sku?: string; barcode?: string | null; variantLabel?: string | null; priceMinor: number; currency: string; available: number };

/** True when the page is used with a mouse and keyboard, where focusing the scan box is welcome (on a phone it would pop the keyboard up). */
export const finePointer = () => typeof window !== "undefined" && window.matchMedia?.("(pointer: fine)").matches;

/**
 * The till's product box: type a letter, a name, a SKU or a barcode and the matching products drop down (an empty box lists the first products,
 * so a short catalog can simply be picked from the list). A laser scanner types the code and presses Enter, which adds the exact product.
 */
export function ProductPicker({ onPick, onCode, inputRef, busy }: { onPick: (f: Found) => void; onCode: (code: string) => Promise<void>; inputRef: React.RefObject<HTMLInputElement | null>; busy?: boolean }) {
  const { t, locale } = useI18n();
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<Found[]>([]);
  const [active, setActive] = useState(-1);
  const [loading, setLoading] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    if (!open) return;
    let stale = false;
    const id = setTimeout(() => {
      setLoading(true);
      api<Found[]>(`store/pos/search?limit=30&q=${encodeURIComponent(text.trim())}`).then((r) => { if (!stale) { setRows(r); setActive(-1); } }).catch(() => { if (!stale) setRows([]); }).finally(() => { if (!stale) setLoading(false); });
    }, text ? 180 : 0);
    return () => { stale = true; clearTimeout(id); };
  }, [text, open]);

  useEffect(() => {
    const away = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, []);

  const pick = (f: Found) => { onPick(f); setText(""); setOpen(false); if (finePointer()) inputRef.current?.focus(); };
  const submit = async () => {
    if (active >= 0 && rows[active]) { pick(rows[active]); return; }
    const c = text.trim();
    if (!c) return;
    // an exact barcode or SKU is what a scanner sends; a name that matches one product also just adds it
    const exact = rows.find((r) => r.barcode === c || r.sku === c);
    if (exact) { pick(exact); return; }
    try { await onCode(c); setText(""); setOpen(false); } catch (e) { if (rows.length === 1) pick(rows[0]); else throw e; }
  };

  return (
    <div ref={box} className="relative min-w-0 flex-1">
      <input
        ref={inputRef}
        value={text}
        autoFocus={finePointer()}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        autoComplete="off"
        placeholder={t("pos.pickHint")}
        onFocus={() => setOpen(true)}
        onChange={(e) => { setText(e.target.value); setOpen(true); }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") { e.preventDefault(); setOpen(true); setActive((a) => Math.min(a + 1, rows.length - 1)); }
          else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, -1)); }
          else if (e.key === "Escape") setOpen(false);
          else if (e.key === "Enter") { e.preventDefault(); void submit().catch(() => undefined); }
        }}
        className="w-full rounded-xl border px-3 py-2.5 text-sm outline-none focus:border-brand"
        disabled={busy}
      />
      {open && (
        <ul id={listId} role="listbox" className="absolute z-30 mt-1 max-h-72 w-full overflow-auto rounded-xl border bg-white py-1 shadow-lg">
          {loading && rows.length === 0 && <li className="px-3 py-2 text-sm text-slate-500">{t("pos.searching")}</li>}
          {!loading && rows.length === 0 && <li className="px-3 py-2 text-sm text-slate-500">{t("pos.noResults")}</li>}
          {rows.map((r, i) => (
            <li key={r.variantId} role="option" aria-selected={i === active} onMouseDown={(e) => e.preventDefault()} onClick={() => pick(r)} onMouseEnter={() => setActive(i)}
              className={`flex cursor-pointer items-center gap-3 px-3 py-2 text-sm ${i === active ? "bg-brand/10" : ""} ${r.available <= 0 ? "opacity-50" : ""}`}>
              <div className="min-w-0 flex-1"><div className="truncate font-medium">{r.name}{r.variantLabel ? ` · ${r.variantLabel}` : ""}</div><div dir="ltr" className="truncate text-start text-xs text-slate-500">{r.sku}{r.barcode ? ` · ${r.barcode}` : ""}</div></div>
              <div className="text-end"><div className="font-semibold">{money(r.priceMinor, r.currency, locale)}</div><div className="text-xs text-slate-500">{t("pos.stock")} {r.available}</div></div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * A laser (keyboard-wedge) scanner types the code very fast and ends with Enter. This listens for such a burst anywhere on the page, even when no box has focus,
 * and hands the code over. Normal typing in a form field is left alone.
 */
export function useWedgeScanner(onCode: (code: string) => void, enabled = true) {
  const ref = useRef(onCode);
  useEffect(() => { ref.current = onCode; });
  useEffect(() => {
    if (!enabled) return;
    let buf = "";
    let last = 0;
    const handler = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT" || el.isContentEditable)) return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const now = Date.now();
      if (e.key === "Enter") {
        if (buf.length >= 4) { e.preventDefault(); ref.current(buf); }
        buf = "";
        return;
      }
      if (e.key.length !== 1) return;
      buf = now - last > 80 ? e.key : buf + e.key;   // a person types slower than 80 ms per key; a scanner much faster
      last = now;
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [enabled]);
}
