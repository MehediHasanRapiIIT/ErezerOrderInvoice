import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { BdDatePipe, bdToday } from './bd-date.pipe';

describe('BdDatePipe', () => {
  const pipe = () => TestBed.runInInjectionContext(() => new BdDatePipe());

  it('shows instants in Bangladesh time with a 12-hour clock', () => {
    // 17:32 UTC is 11:32 PM in Dhaka, whatever the computer's time zone.
    expect(pipe().transform('2026-10-18T17:32:00Z')).toBe('18 Oct 2026, 11:32 PM');
    expect(pipe().transform('2026-10-18T19:05:00Z')).toBe('19 Oct 2026, 1:05 AM'); // already the next day in Dhaka
  });

  it('keeps plain calendar dates unchanged', () => {
    expect(pipe().transform('2026-10-18', 'd MMM y')).toBe('18 Oct 2026');
  });

  it('returns null for missing values', () => {
    expect(pipe().transform(null)).toBeNull();
  });
});

describe('bdToday', () => {
  it('uses the Bangladesh calendar day', () => {
    const d = bdToday(new Date('2026-10-18T19:00:00Z')); // 1 AM on the 19th in Dhaka
    expect([d.getFullYear(), d.getMonth() + 1, d.getDate()]).toEqual([2026, 10, 19]);
  });
});
