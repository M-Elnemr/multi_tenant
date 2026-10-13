"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/client";

export type SectionForm<T> = {
  loaded: boolean; v: Partial<T>; set: (patch: Partial<T>) => void;
  dirty: boolean; saving: boolean; saved: boolean; error: unknown; errors: Record<string, string>;
  save: () => Promise<boolean>; reset: () => void;
};

type Opts<T> = {
  /** Field problems by key; Save stays disabled while there are any. */
  validate?: (v: Partial<T>) => Record<string, string>;
  /** Turns the changed fields into the request body (e.g. an empty cover becomes ""). */
  toBody?: (changed: Partial<T>) => Record<string, unknown>;
  onSaved?: () => void;
};

const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/**
 * One section's editor state. It loads the profile once, keeps a draft of ONLY the listed [keys], and saves ONLY what changed, so an editor can
 * never touch another section's data.
 */
export function useSectionForm<T extends object>(path: string, keys: readonly (keyof T & string)[], opts: Opts<T> = {}): SectionForm<T> {
  const [base, setBase] = useState<Partial<T> | null>(null);
  const [draft, setDraft] = useState<Partial<T>>({});
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let live = true;
    api<Record<string, unknown>>(path).then((r) => {
      if (!live) return;
      const picked: Record<string, unknown> = {};
      for (const k of keys) picked[k] = r[k];
      setBase(picked as Partial<T>);
      setDraft(picked as Partial<T>);
    }).catch((e) => { if (live) setError(e); });
    return () => { live = false; };
  // the keys are a fixed list for each editor: only a different path reloads the profile
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  const set = useCallback((patch: Partial<T>) => { setSaved(false); setDraft((d) => ({ ...d, ...patch })); }, []);
  const changed = (): Partial<T> => {
    const out: Record<string, unknown> = {};
    for (const k of keys) if (!same((draft as Record<string, unknown>)[k], (base as Record<string, unknown> | null)?.[k])) out[k] = (draft as Record<string, unknown>)[k];
    return out as Partial<T>;
  };
  const errors = opts.validate ? opts.validate(draft) : {};
  const dirty = base !== null && Object.keys(changed()).length > 0;

  const save = useCallback(async () => {
    const c = changed();
    if (Object.keys(c).length === 0) return true;
    setSaving(true); setError(null);
    try {
      const body = opts.toBody ? opts.toBody(c) : (c as Record<string, unknown>);
      await api(path, { method: "PATCH", body });
      setBase(draft); setSaved(true);
      opts.onSaved?.();
      return true;
    } catch (e) { setError(e); return false; } finally { setSaving(false); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, draft, base]);

  return { loaded: base !== null, v: draft, set, dirty, saving, saved, error, errors, save, reset: () => { setDraft(base ?? {}); setSaved(false); setError(null); } };
}
