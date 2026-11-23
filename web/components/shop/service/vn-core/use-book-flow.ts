"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import type { BookInit, VnConfig } from "./types";

/** What the backend answers to a booking; nothing is shown as confirmed unless `status` says so. */
export type BookDone = { bookingNumber: string; status: string; serviceName: string; date: string; time: string; priceMinor?: number; currency?: string };

export const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/**
 * The booking logic every service template shares (same endpoints and payload as the original booking page): service, options, day, a time from the
 * backend's free slots, contact details, then POST shop/booking. Templates only decide how it looks.
 */
export function useBookFlow(cfg: VnConfig, init: BookInit = {}) {
  const [svc, setSvc] = useState(cfg.services.some((s) => s.id === init.service) ? init.service! : cfg.services[0]?.id ?? "");
  const [date, setDate] = useState(init.date ?? "");
  const [time, setTime] = useState(init.time ?? "");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [note, setNote] = useState("");
  const [addons, setAddons] = useState<string[]>([]);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [where, setWhere] = useState<"SHOP" | "HOME">("SHOP");
  const [address, setAddress] = useState("");
  const [staff, setStaff] = useState("");
  const [done, setDone] = useState<BookDone | null>(null);
  const [touched, setTouched] = useState(false);
  const slots = useApi<string[]>(svc && date ? `shop/booking/slots?serviceId=${svc}&date=${date}${addons.map((a) => `&addonIds=${a}`).join("")}${staff ? `&staffId=${staff}` : ""}` : null);
  const today = new Date();
  const max = new Date(today.getTime() + cfg.maxDaysAhead * 86400000);
  const chosen = cfg.services.find((s) => s.id === svc);
  const mode = chosen?.locationMode ?? "SHOP";
  const place: "SHOP" | "HOME" = mode === "BOTH" ? where : mode;
  const extra = (chosen?.addons ?? []).filter((a) => addons.includes(a.id));
  const total = (chosen?.priceMinor ?? 0) + extra.reduce((s, a) => s + a.priceMinor, 0);
  const minutes = (chosen?.durationMinutes ?? 0) + extra.reduce((s, a) => s + a.durationMinutes, 0);
  const pick = (id: string) => { setSvc(id); setTime(""); setAddons([]); setAnswers({}); setWhere("SHOP"); setStaff(""); };
  const send = useAction(async () => {
    try {
      setDone(await api<BookDone>("shop/booking", { body: { serviceId: svc, date, time, name: name.trim(), phone, note: note.trim() || undefined, addonIds: addons, answers, locationMode: place, address: place === "HOME" ? address.trim() : undefined, staffId: staff || undefined } }));
    } catch (e) {
      // the time may have just been taken: refresh the real free times and ask for another one, never pretend it worked
      setTime(""); void slots.reload();
      throw e;
    }
  });
  const missing = (chosen?.requiredFields ?? []).some((q) => q.required !== false && !(answers[q.key] ?? "").trim());
  const errors = {
    service: !svc, date: !date, time: !time, name: !name.trim(), phone: phone.replace(/\D/g, "").length < 9,
    address: place === "HOME" && address.trim().length < 6, fields: missing,
  };
  const ready = !Object.values(errors).some(Boolean);
  const submit = () => { setTouched(true); if (ready && !send.loading) void send.run(); };
  const fieldError = (send.error as ApiError | null)?.fields ?? undefined;
  return {
    svc, chosen, pick, date, setDate: (d: string) => { setDate(d); setTime(""); }, time, setTime, name, setName, phone, setPhone, note, setNote, addons, setAddons: (a: string[]) => { setAddons(a); setTime(""); }, answers, setAnswers,
    where, setWhere, address, setAddress, staff, setStaff: (s: string) => { setStaff(s); setTime(""); }, done, slots, today, max, mode, place, total, minutes, send, errors, ready, touched, submit, fieldError,
  };
}
export type BookFlow = ReturnType<typeof useBookFlow>;

export type Found = { bookingNumber: string; status: string; serviceName: string; date: string; time: string; priceMinor?: number; currency?: string };
/** Customer booking management with the two public endpoints that exist: look a booking up by number + phone, cancel it. */
export function useManage() {
  const [number, setNumber] = useState("");
  const [phone, setPhone] = useState("");
  const [found, setFound] = useState<Found | null>(null);
  const [cancelled, setCancelled] = useState(false);
  const look = useAction(async () => { setCancelled(false); setFound(await api<Found>(`shop/booking/lookup?number=${encodeURIComponent(number.trim())}&phone=${encodeURIComponent(phone)}`)); });
  const cancel = useAction(async () => { const r = await api<{ status: string }>("shop/booking/cancel", { body: { bookingNumber: found?.bookingNumber, phone } }); setFound((f) => (f ? { ...f, status: r.status } : f)); setCancelled(true); });
  const ready = number.trim().length >= 3 && phone.replace(/\D/g, "").length >= 9;
  return { number, setNumber, phone, setPhone, found, cancelled, look, cancel, ready, reset: () => { setFound(null); setCancelled(false); } };
}
