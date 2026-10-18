import { dateBounds, isoDate } from './date-range';

describe('date ranges', () => {
  it('formats local dates without shifting to UTC', () => {
    expect(isoDate(new Date(2026, 9, 15, 0, 30))).toBe('2026-10-15');
  });

  it('builds inclusive bounds ending today', () => {
    const today = isoDate(new Date());
    expect(dateBounds('today')).toEqual({ from: today, to: today });
    expect(dateBounds('7d').to).toBe(today);
    expect(dateBounds('month').from?.endsWith('-01')).toBe(true);
  });

  it('leaves all-time and custom ranges open', () => {
    expect(dateBounds('all')).toEqual({});
    expect(dateBounds('custom')).toEqual({});
  });
});
