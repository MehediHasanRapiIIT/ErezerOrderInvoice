import { dateBounds, isoDate } from './date-range';
import { bdToday } from './ui/bd-date.pipe';

describe('date ranges', () => {
  it('formats local dates without shifting to UTC', () => {
    expect(isoDate(new Date(2026, 9, 15, 0, 30))).toBe('2026-10-15');
  });

  it('builds inclusive bounds ending today (the Bangladesh calendar day)', () => {
    const today = isoDate(bdToday());
    expect(dateBounds('today')).toEqual({ from: today, to: today });
    expect(dateBounds('7d').to).toBe(today);
    expect(dateBounds('month').from?.endsWith('-01')).toBe(true);
  });

  it('leaves all-time and custom ranges open', () => {
    expect(dateBounds('all')).toEqual({});
    expect(dateBounds('custom')).toEqual({});
  });
});
