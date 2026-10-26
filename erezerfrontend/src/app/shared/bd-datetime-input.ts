/** Converting between instants and `<input type="datetime-local">` values, which are read as Bangladesh time. */

const DHAKA_OFFSET_MS = 6 * 60 * 60 * 1000;

/** "2026-10-26T14:30" in Bangladesh time for an instant (default: now). */
export function toBdInputValue(instant: string | Date = new Date()): string {
  const date = typeof instant === 'string' ? new Date(instant) : instant;
  return new Date(date.getTime() + DHAKA_OFFSET_MS).toISOString().slice(0, 16);
}

/** The instant (ISO) for a Bangladesh-time input value; null when empty or invalid. */
export function fromBdInputValue(value: string): string | null {
  if (!value) return null;
  const date = new Date(`${value.length === 16 ? value + ':00' : value}+06:00`);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
