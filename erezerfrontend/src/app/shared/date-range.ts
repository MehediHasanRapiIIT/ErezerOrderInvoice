import { bdToday } from './ui/bd-date.pipe';

/** Quick date-range presets shared by list filters. */
export type DateRange = 'all' | 'today' | '7d' | '30d' | 'month' | 'custom';

export const DATE_RANGES: { value: DateRange; label: string }[] = [
  { value: 'all', label: 'All time' },
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'month', label: 'This month' },
];

/** Local calendar date as YYYY-MM-DD (the backend interprets dates in Asia/Dhaka). */
export function isoDate(date: Date): string {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}

/** Inclusive from/to for a preset; empty for "all" and "custom" (custom dates come from the caller). */
export function dateBounds(range: DateRange): { from?: string; to?: string } {
  const today = bdToday();
  const daysAgo = (n: number) => new Date(today.getFullYear(), today.getMonth(), today.getDate() - n);
  switch (range) {
    case 'today':
      return { from: isoDate(today), to: isoDate(today) };
    case '7d':
      return { from: isoDate(daysAgo(6)), to: isoDate(today) };
    case '30d':
      return { from: isoDate(daysAgo(29)), to: isoDate(today) };
    case 'month':
      return { from: isoDate(new Date(today.getFullYear(), today.getMonth(), 1)), to: isoDate(today) };
    default:
      return {};
  }
}
