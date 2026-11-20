"use client";

import { useI18n } from "@/components/i18n-provider";
import { Badge } from "@/components/ui";

export type Service = { id: string; name: string; description?: string | null; durationMinutes: number; bufferMinutes: number; priceMinor: number; currency: string; isActive: boolean; sortOrder: number;
  categoryId?: string | null; categoryName?: string | null; shortDescription?: string | null; priceBeforeMinor?: number | null; maxConcurrent?: number | null; locationMode?: "SHOP" | "HOME" | "BOTH";
  serviceArea?: string | null; instructions?: string | null; cancellationPolicy?: string | null; imageFileId?: string | null; gallery?: string[];
  requiredFields?: { key: string; label: string; required?: boolean }[]; addons?: Addon[] };
export type Addon = { id: string; name: string; priceMinor: number; durationMinutes: number; isActive?: boolean };
export type Category = { id: string; name: string; sortOrder: number };
export type Booking = {
  id: string; bookingNumber: string; serviceId: string; serviceName: string; customerName: string; phone: string; startsAt: string; date: string; time: string; endTime: string; weekday: string;
  priceMinor: number; currency: string; status: Status; source: "AI_CHAT" | "DASHBOARD" | "WEBSITE"; note?: string | null; declineReason?: string | null;
  answers?: Record<string, string>; addons?: { id: string; name: string; priceMinor: number; durationMinutes: number }[]; locationMode?: "SHOP" | "HOME" | null; address?: string | null;
};
export type Status = "PENDING" | "CONFIRMED" | "DECLINED" | "CANCELLED" | "COMPLETED" | "NO_SHOW";
export type Range = { weekday: number; opens: string; closes: string };
export type Settings = { stations: number; slotStepMinutes: number; minNoticeMinutes: number; maxDaysAhead: number; autoConfirm: boolean; timezone: string; pendingExpiresMinutes?: number | null; cancelDeadlineHours?: number };

const TONE: Record<Status, "amber" | "green" | "red" | "slate" | "blue" | "violet"> = { PENDING: "amber", CONFIRMED: "green", DECLINED: "red", CANCELLED: "slate", COMPLETED: "blue", NO_SHOW: "violet" };

export function BookingStatus({ status }: { status: Status }) {
  const { t } = useI18n();
  return <Badge tone={TONE[status]}>{t(`bk.status.${status}`)}</Badge>;
}

/** Saturday first (the Egyptian week), as ISO weekday numbers (1 = Monday ... 7 = Sunday). */
export const WEEK = [6, 7, 1, 2, 3, 4, 5];
