import { generatePassword, PASSWORD_PATTERN } from './password';

describe('generatePassword', () => {
  it('always satisfies the backend password policy', () => {
    for (let i = 0; i < 200; i++) {
      expect(PASSWORD_PATTERN.test(generatePassword())).toBe(true);
    }
  });

  it('avoids look-alike characters', () => {
    const sample = Array.from({ length: 50 }, () => generatePassword(20)).join('');
    expect(sample).not.toMatch(/[0O1lI]/);
  });
});
