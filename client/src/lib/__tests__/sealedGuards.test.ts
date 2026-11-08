/// <reference types="jest" />

import { SealedError } from '../sealed';
import { requireDistinctPassphrase } from '../sealedVault';

// jest.mock calls are hoisted above these imports by babel-jest.
// Only the pure guard is under test; the modules around it need native APIs.
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
  randomUUID: () => '00000000-0000-4000-8000-000000000000',
}));
jest.mock('expo-secure-store', () => ({ WHEN_UNLOCKED_THIS_DEVICE_ONLY: 0 }));
jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
jest.mock('expo-file-system', () => ({ File: class {}, Directory: class {}, Paths: {}, FileMode: {} }));
jest.mock('expo-image-manipulator', () => ({}));
jest.mock('@/api/lock', () => ({ api: {} }));
jest.mock('@/api/client', () => ({ ApiError: class extends Error {} }));

/** Security review F3: the server sees the account password, so it must never be the passphrase. */
describe('Sealed Vault passphrase guard', () => {
  test('refuses the account password, also when it differs only by Unicode normalisation', () => {
    expect(() => requireDistinctPassphrase('café terrace 12', 'café terrace 12')).toThrow(SealedError);
    expect(() => requireDistinctPassphrase('cafe\u0301 terrace 12', 'caf\u00e9 terrace 12')).toThrow(SealedError);
  });

  test('accepts a different, strong passphrase', () => {
    expect(() => requireDistinctPassphrase('account-password-1', 'maple river cloud tiger')).not.toThrow();
  });

  test('refuses a passphrase that is too easy to guess (review F9)', () => {
    expect(() => requireDistinctPassphrase('account-password-1', 'Summer2026!!')).toThrow(SealedError);
  });
});
