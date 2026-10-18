/**
 * Normalises a phone number like the backend's customer key: digits only, and a leading Bangladesh
 * country code 880 becomes the local 0 ("+880 1711-111111" → "01711111111").
 */
export function phoneKey(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  return digits.startsWith('880') ? digits.slice(2) : digits;
}

/** A complete Bangladeshi mobile number has 11 digits. */
export const COMPLETE_PHONE_DIGITS = 11;
