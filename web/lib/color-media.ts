/** A picture tagged with an option value (usually a colour) shows when that value is chosen; untagged pictures belong to every colour. */
export function picturesFor<T extends { optionValue?: string | null }>(items: T[], chosen: string[]): T[] {
  const mine = items.filter((m) => m.optionValue && chosen.includes(m.optionValue));
  const general = items.filter((m) => !m.optionValue);
  if (mine.length) return [...mine, ...general];
  return general.length ? general : items;   // nothing for this colour and nothing general: better the others than an empty page
}
