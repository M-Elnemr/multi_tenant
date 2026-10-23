/**
 * Name of a delivery method as the shopper should read it. Every new shop gets "Store pickup" and "Standard delivery" saved in English;
 * those two are shown in the visitor's language. A name the owner typed is shown exactly as typed.
 */
export function shipName(t: (key: string) => string, m: { type: string; name: string }): string {
  const n = m.name.trim().toLowerCase();
  if (m.type === "PICKUP" && n === "store pickup") return t("ship.PICKUP");
  if (m.type === "FIXED" && n === "standard delivery") return t("ship.standard");
  return m.name;
}
