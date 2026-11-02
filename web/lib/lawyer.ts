/** Shapes of the lawyer API and the small helpers every lawyer screen shares. */
export const CASE_TYPES = ["CIVIL", "CRIMINAL", "FAMILY", "COMMERCIAL", "LABOUR", "REAL_ESTATE", "ADMINISTRATIVE", "ECONOMIC", "INHERITANCE", "CONTRACTS", "IP", "TAX", "ARBITRATION", "CASSATION", "CONSULTATION", "OTHER"] as const;
export const CASE_STATUSES = ["UNDER_STUDY", "OPEN", "CLOSED"] as const;
export const OUTCOMES = ["WON", "LOST", "SETTLED"] as const;
export const STEP_KINDS = ["HEARING", "DEADLINE", "FILING", "MEETING", "OTHER"] as const;
export const PAY_METHODS = ["CASH", "TRANSFER", "INSTAPAY", "WALLET", "CHEQUE", "OTHER"] as const;

export type CaseRow = { id: string; number: string; title: string; caseType: string; court: string; courtCaseNo: string; opponent: string; status: string; outcome: string; openedOn: string; closedOn?: string | null; clientNames?: string | null; nextStepAt?: string | null; billedMinor: number; paidMinor: number };
export type Client = { id: string; name: string; phone: string; phone2?: string; email: string; nationalId?: string; address?: string; kind: "PERSON" | "COMPANY"; notes?: string; tags: string[]; caseCount?: number; openCases?: number; balanceMinor?: number; createdAt: string; portalEnabled?: boolean; portalLastLoginAt?: string | null };
export type Step = { id: string; kind: string; startsAt: string; durationMinutes: number; description: string; location: string; done: boolean; result: string };
export type Action = { id: string; actionDate: string; title: string; notes: string; feeMinor: number };
export type Payment = { id: string; amountMinor: number; paidOn: string; method: string; note: string; clientId?: string | null; clientName?: string | null };
export type Doc = { id: string; name: string; url: string; contentType: string; createdAt: string };
export type CaseDetail = Omit<CaseRow, "clientNames" | "nextStepAt"> & {
  circuit: string; opponentLawyer: string; clientRole: string; description: string; agreedFeeMinor: number; assignedTo?: string | null; assignedName?: string | null;
  clients: { id: string; name: string; phone: string; email: string; kind: string }[]; actions: Action[]; steps: Step[]; payments: Payment[]; documents: Doc[];
  notes: { id: string; startsAt: string; title: string; body: string }[]; actionFeesMinor: number; balanceMinor: number;
};
export type Booking = { id: string; code: string; guestName: string; guestPhone: string; topic: string; startsAt: string; endsAt: string; status: string; decisionNote: string; clientId?: string | null; caseId?: string | null; serviceName?: string | null; mode?: string | null; priceMinor?: number | null; createdAt: string };
export type CalItem = { kind: "BOOKING" | "STEP" | "NOTE"; id: string; startsAt: string; endsAt: string; title: string; status?: string; stepKind?: string; location?: string; done?: boolean; body?: string; phone?: string; color?: string; allDay?: boolean; caseId?: string | null; caseNumber?: string | null; caseTitle?: string | null; code?: string };
export type Service = { id: string; name: string; description: string; durationMinutes: number; priceMinor: number | null; mode: "OFFICE" | "ONLINE" | "PHONE"; isActive: boolean; sortOrder: number };

/** Badge tone per case status / result. */
export const statusTone: Record<string, "green" | "amber" | "red" | "blue" | "slate"> = { OPEN: "blue", UNDER_STUDY: "amber", CLOSED: "slate", WON: "green", LOST: "red", SETTLED: "blue", NONE: "slate",
  PENDING: "amber", APPROVED: "green", DENIED: "red", CANCELLED: "slate", DONE: "green", NO_SHOW: "red" };

/** Calendar colours: bookings teal, hearings indigo, deadlines red, other steps violet, notes amber. */
export function calColor(i: Pick<CalItem, "kind" | "stepKind" | "status" | "color">): { bg: string; fg: string; dot: string } {
  if (i.kind === "NOTE" && i.color) return { bg: `${i.color}22`, fg: i.color, dot: i.color };
  if (i.kind === "BOOKING") return i.status === "PENDING" ? { bg: "#fef3c7", fg: "#92400e", dot: "#f59e0b" } : { bg: "#ccfbf1", fg: "#115e59", dot: "#14b8a6" };
  if (i.kind === "NOTE") return { bg: "#fef9c3", fg: "#854d0e", dot: "#eab308" };
  if (i.stepKind === "DEADLINE") return { bg: "#fee2e2", fg: "#991b1b", dot: "#ef4444" };
  if (i.stepKind === "HEARING") return { bg: "#e0e7ff", fg: "#3730a3", dot: "#6366f1" };
  return { bg: "#ede9fe", fg: "#5b21b6", dot: "#8b5cf6" };
}

export const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
/** "2026-11-05" + "10:30" in the visitor's clock -> ISO instant. */
export const at = (day: string, time: string) => new Date(`${day}T${time || "09:00"}`).toISOString();
