/** Mirrors the backend PasswordPolicy: 10-72 characters with at least one letter and one digit. */
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{10,72}$/;

// No look-alike characters (0/O, 1/l/I) so a password read aloud or copied by hand survives.
const LETTERS = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
const DIGITS = '23456789';

/** Cryptographically random password that satisfies the policy. */
export function generatePassword(length = 12): string {
  const pool = LETTERS + DIGITS;
  const pick = (chars: string) => chars[crypto.getRandomValues(new Uint32Array(1))[0] % chars.length];
  const chars = [pick(LETTERS), pick(DIGITS), ...Array.from({ length: length - 2 }, () => pick(pool))];
  // Shuffle so the guaranteed letter and digit are not always first.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = crypto.getRandomValues(new Uint32Array(1))[0] % (i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}
