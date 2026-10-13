"use client";

import { AnimatePresence, motion } from "motion/react";
import { useEffect, type ReactNode } from "react";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { Button, ErrorText, Loading } from "../ui";
import type { SectionForm } from "./use-section-form";

/**
 * The side panel every section editor opens in. The page stays visible next to it. With a [form] the footer has Save/Cancel (Save is enabled
 * only when something changed and nothing is invalid, and closing with unsaved changes asks first); without one the footer just says Done.
 */
export function DrawerShell<T>({ title, description, icon, form, onClose, children, wide }: {
  title: string; description?: string; icon: IconName; form?: SectionForm<T>; onClose: () => void; children: ReactNode; wide?: boolean;
}) {
  const { t, locale } = useI18n();
  const close = () => { if (form?.dirty && !window.confirm(t("se.discard"))) return; onClose(); };
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  });
  const hasErrors = form ? Object.keys(form.errors).length > 0 : false;
  const from = locale === "ar" ? "-100%" : "100%";
  return (
    <AnimatePresence>
      <motion.div key="backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="fixed inset-0 z-50 bg-slate-900/25 backdrop-blur-[1px]" onClick={close} />
      <motion.aside key="panel" role="dialog" aria-modal="true" aria-label={title} initial={{ x: from }} animate={{ x: 0 }} exit={{ x: from }} transition={{ type: "spring", damping: 32, stiffness: 320 }}
        className={`fixed inset-y-0 end-0 z-50 flex w-full flex-col bg-white shadow-2xl ${wide ? "sm:max-w-2xl" : "sm:max-w-lg"}`}>
        <header className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-brand-soft text-brand"><Icon name={icon} className="h-5 w-5" /></span>
          <div className="min-w-0 flex-1"><h2 className="text-lg font-extrabold text-ink">{title}</h2>{description && <p className="mt-0.5 text-sm text-slate-500">{description}</p>}</div>
          <button onClick={close} aria-label={t("common.close")} className="rounded-full p-2 text-slate-500 transition hover:rotate-90 hover:bg-slate-100"><Icon name="close" className="h-5 w-5" /></button>
        </header>
        <div className="flex-1 space-y-5 overflow-y-auto px-5 py-5">
          {form && !form.loaded ? <Loading /> : children}
          {form && <ErrorText error={form.error} />}
        </div>
        <footer className="flex items-center justify-between gap-3 border-t border-slate-100 bg-white/90 px-5 py-3.5 backdrop-blur">
          {form ? (
            <>
              <span className={`flex items-center gap-1.5 text-sm font-semibold transition ${form.saved && !form.dirty ? "text-emerald-600" : "text-amber-600"}`}>
                {form.saved && !form.dirty ? <><Icon name="check" className="h-4 w-4" />{t("common.saved")}</> : form.dirty ? <><span className="h-2 w-2 rounded-full bg-amber-500" />{t("se.unsaved")}</> : null}
              </span>
              <div className="flex gap-2">
                <Button variant="ghost" onClick={close}>{form.saved && !form.dirty ? t("se.done") : t("common.cancel")}</Button>
                <Button loading={form.saving} disabled={!form.dirty || hasErrors} onClick={() => void form.save()}>{t("common.save")}</Button>
              </div>
            </>
          ) : <Button className="ms-auto" onClick={onClose}>{t("se.done")}</Button>}
        </footer>
      </motion.aside>
    </AnimatePresence>
  );
}
