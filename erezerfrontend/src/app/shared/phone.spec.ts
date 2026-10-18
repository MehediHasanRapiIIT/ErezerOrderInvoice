import { phoneKey } from './phone';

describe('phoneKey', () => {
  it('treats every common format of one number as the same key', () => {
    for (const phone of ['01711111111', '01711 111111', '+880 1711-111111', '8801711111111', '+8801711111111']) {
      expect(phoneKey(phone)).toBe('01711111111');
    }
  });
});
