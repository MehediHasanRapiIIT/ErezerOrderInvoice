import { describe, expect, it } from 'vitest';
import { COURIER_STATUS_LABELS, isValidCod } from './courier.api';

describe('isValidCod', () => {
  it('accepts whole taka and paisa within Steadfast limits', () => {
    expect(isValidCod(0)).toBe(true);
    expect(isValidCod(1260)).toBe(true);
    expect(isValidCod(560.5)).toBe(true);
    expect(isValidCod(560.55)).toBe(true);
    expect(isValidCod(1_000_000)).toBe(true);
  });

  it('rejects empty, negative, too large and more than 2 decimals', () => {
    expect(isValidCod(null)).toBe(false);
    expect(isValidCod(undefined)).toBe(false);
    expect(isValidCod(-1)).toBe(false);
    expect(isValidCod(1_000_000.01)).toBe(false);
    expect(isValidCod(560.555)).toBe(false);
  });
});

describe('COURIER_STATUS_LABELS', () => {
  it('names the statuses from the official guide', () => {
    expect(COURIER_STATUS_LABELS['pending']).toBe('With courier (not yet attempted)');
    expect(COURIER_STATUS_LABELS['exceptional']).toContain('Lost or damaged');
  });
});
