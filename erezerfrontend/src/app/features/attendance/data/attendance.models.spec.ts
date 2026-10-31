import { hoursLabel, mapLink, time12 } from './attendance.models';

describe('attendance helpers', () => {
  it('shows shift times on a 12-hour clock', () => {
    expect(time12('00:05:00')).toBe('12:05 AM');
    expect(time12('09:30:00')).toBe('9:30 AM');
    expect(time12('12:00:00')).toBe('12:00 PM');
    expect(time12('18:45:00')).toBe('6:45 PM');
    expect(time12(undefined)).toBe('–');
  });

  it('shows minutes as hours and minutes', () => {
    expect(hoursLabel(0)).toBe('0h 0m');
    expect(hoursLabel(450)).toBe('7h 30m');
    expect(hoursLabel(12_605)).toBe('210h 5m');
  });

  it('links a position to the map only when there is one', () => {
    expect(mapLink(23.78, 90.4)).toBe('https://www.google.com/maps?q=23.78,90.4');
    expect(mapLink(undefined, undefined)).toBeNull();
  });
});
