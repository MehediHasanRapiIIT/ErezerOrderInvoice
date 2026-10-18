import { formatDate } from '@angular/common';
import { LOCALE_ID, Pipe, PipeTransform, inject } from '@angular/core';

/** Bangladesh has one fixed offset (no daylight saving), so this is exact for every date. */
export const BD_OFFSET = '+0600';
export const BD_TIME_ZONE = 'Asia/Dhaka';

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Formats dates in Bangladesh time with a 12-hour clock, whatever the viewer's computer time zone is:
 * `{{ order.createdAt | bdDate }}` → "18 Oct 2026, 11:32 PM".
 *
 * Plain calendar dates ("2026-10-18", e.g. an expense date) have no time zone and are shown as they are.
 */
@Pipe({ name: 'bdDate' })
export class BdDatePipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: string | number | Date | null | undefined, format = 'd MMM y, h:mm a'): string | null {
    if (value === null || value === undefined || value === '') {
      return null;
    }
    if (typeof value === 'string' && DATE_ONLY.test(value)) {
      return formatDate(value, format, this.locale);
    }
    return formatDate(value, format, this.locale, BD_OFFSET);
  }
}

/** Today's calendar date in Bangladesh, as a local Date at midnight (for date pickers and range presets). */
export function bdToday(now: Date = new Date()): Date {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: BD_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(now)
    .split('-')
    .map(Number);
  return new Date(parts[0], parts[1] - 1, parts[2]);
}
