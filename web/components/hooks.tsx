"use client";

import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/client";

export type Page<T> = { data: T[]; meta: { page: number; pageSize: number; total: number; hasNext: boolean } };

/**
 * Fetches a backend path through the BFF. `reload()` re-fetches (call it after mutations). Pass null to skip.
 * State is derived from the latest completed request, so there is no "reset state in an effect" step.
 */
export function useApi<T>(path: string | null) {
  const [tick, setTick] = useState(0);
  const [res, setRes] = useState<{ key: string; data: T | null; error: ApiError | null }>({ key: "", data: null, error: null });
  const key = path === null ? "" : `${path}#${tick}`;

  useEffect(() => {
    if (path === null) return;
    let cancelled = false;
    api<T>(path)
      .then((d) => { if (!cancelled) setRes({ key, data: d, error: null }); })
      .catch((e: ApiError) => { if (!cancelled) setRes((prev) => ({ key, data: prev.data, error: e })); });
    return () => { cancelled = true; };
  }, [path, key]);

  const reload = useCallback(async () => { setTick((t) => t + 1); }, []);
  return { data: path === null ? null : res.data, error: path === null ? null : res.error, loading: path !== null && res.key !== key, reload };
}

/** Runs an async action with loading + error state (for buttons and forms). */
export function useAction<A extends unknown[], R>(fn: (...args: A) => Promise<R>) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const run = useCallback(async (...args: A): Promise<R | undefined> => {
    setLoading(true);
    setError(null);
    try {
      return await fn(...args);
    } catch (e) {
      setError(e);
      return undefined;
    } finally {
      setLoading(false);
    }
  }, [fn]);
  return { run, loading, error, setError };
}

export type Me = { id: string; firstName: string; lastName: string; phone?: string; email?: string; roles: string[]; permissions: string[] };

export function useMe() {
  const [state, setState] = useState<{ me: Me | null; ready: boolean }>({ me: null, ready: false });
  useEffect(() => {
    let alive = true;
    const load = () => {
      api<Me>("auth/me")
        .then((me) => alive && setState({ me, ready: true }))
        .catch(() => alive && setState({ me: null, ready: true }));
    };
    load();
    window.addEventListener("auth:changed", load);
    return () => { alive = false; window.removeEventListener("auth:changed", load); };
  }, []);
  return { me: state.me, ready: state.ready, can: (p: string) => !!state.me?.permissions.includes(p) };
}
