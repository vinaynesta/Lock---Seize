/// <reference types="jest" />

import { MIN_BITS, passphraseStrength } from '../passphraseStrength';

/** Security review F9: the Sealed Vault passphrase guards an offline-guessable key. */
describe('passphrase strength', () => {
  test.each([
    ['maple river cloud tiger', 'strong'],
    ['my dog Rex loves blue kites', 'strong'],
    ['Tr0ub4dor&3xK9', 'fair'],
    ['Summer2026!!', 'weak'],
    ['passwordpassword', 'weak'],
    ['aaaaaaaaaaaaaaaa', 'weak'],
    ['qwertyuiopasdfgh', 'weak'],
    ['abcdefgh12345678', 'weak'],
  ])('%s is %s', (passphrase, level) => {
    expect(passphraseStrength(passphrase).level).toBe(level);
  });

  test('leaning on your own name or email costs most of the score', () => {
    const plain = passphraseStrength('rosalind harbour 77');
    const personal = passphraseStrength('rosalind harbour 77', ['Rosalind Harbour', 'rosalind@example.com', 'rosalind']);
    expect(personal.bits).toBeLessThan(plain.bits);
    expect(personal.acceptable).toBe(false);
    expect(personal.hint).toContain('name or email');
  });

  test('weak results explain why; strong ones need no hint', () => {
    expect(passphraseStrength('Summer2026!!').hint).not.toBeNull();
    expect(passphraseStrength('maple river cloud tiger').hint).toBeNull();
    expect(passphraseStrength('').acceptable).toBe(false);
    expect(passphraseStrength('maple river cloud tiger').bits).toBeGreaterThanOrEqual(MIN_BITS);
  });
});
