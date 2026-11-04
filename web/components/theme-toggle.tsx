"use client";

import { useEffect, useSyncExternalStore } from "react";
import { Icon } from "./icons";
import { useT } from "./i18n-provider";

/** Workspace theme (dashboards, POS, admin). Stored per browser; public sites never read it. */
export type ThemePref = "light" | "dark" | "system";
const KEY = "dash-theme";
const subs = new Set<() => void>();
const read = (): ThemePref => { try { const v = localStorage.getItem(KEY); return v === "dark" || v === "system" ? v : "light"; } catch { return "light"; } };
const isDark = (p: ThemePref) => p === "dark" || (p === "system" && typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches);

export function applyTheme(p: ThemePref = read()) {
  if (typeof document === "undefined") return;
  if (isDark(p)) document.documentElement.setAttribute("data-theme", "dark");
  else document.documentElement.removeAttribute("data-theme");
}

export function setTheme(p: ThemePref) {
  try { localStorage.setItem(KEY, p); } catch { /* storage blocked: still switch for this visit */ }
  applyTheme(p);
  subs.forEach((f) => f());
}

export function useTheme(): ThemePref {
  return useSyncExternalStore((cb) => { subs.add(cb); return () => { subs.delete(cb); }; }, read, () => "light");
}

/** Mount once in a workspace shell: applies the theme, follows the OS when set to system, and clears it when leaving (public pages stay light). */
export function useWorkspaceTheme() {
  const pref = useTheme();
  useEffect(() => {
    applyTheme(pref);
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const on = () => pref === "system" && applyTheme(pref);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, [pref]);
  useEffect(() => () => document.documentElement.removeAttribute("data-theme"), []);
}

export function ThemeToggle({ className = "" }: { className?: string }) {
  const t = useT();
  const pref = useTheme();
  const next: ThemePref = pref === "light" ? "dark" : pref === "dark" ? "system" : "light";
  const icon = pref === "light" ? "sun" : pref === "dark" ? "moon" : "monitor";
  const label = `${t("theme.label")}: ${t(`theme.${pref}`)}`;
  return (
    <button type="button" onClick={() => setTheme(next)} aria-label={label} title={label}
      className={`grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink active:scale-95 ${className}`}>
      <Icon key={icon} name={icon} className="h-[18px] w-[18px] animate-scale-in" />
    </button>
  );
}
