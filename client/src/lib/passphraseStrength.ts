/**
 * Sealed Vault passphrase strength (security review F9). The wrapped secret key is an offline-guessing
 * target for anyone holding a session token, or for the server itself, protected by Argon2id at the
 * OWASP floor. So the passphrase must carry real entropy. The server never sees it, so the check runs
 * here, in setup, recovery and change.
 *
 * A deliberately small estimator (no 800 KB dictionary library): character-pool entropy discounted for
 * human choice, minus penalties for the patterns guessers try first: repeats, sequences, keyboard
 * walks, common passwords, years, and the account's own name or email. Long multi-word passphrases
 * score well; short "complex" passwords don't.
 */

export type StrengthLevel = 'weak' | 'fair' | 'strong';

export interface Strength {
  /** Rough guessing entropy in bits. */
  bits: number;
  level: StrengthLevel;
  /** True when the passphrase may be used (fair or strong). */
  acceptable: boolean;
  /** The main reason it scored low, for the hint under the field. */
  hint: string | null;
}

/** Below this the passphrase is refused; at STRONG and above it shows as strong. */
export const MIN_BITS = 50;
export const STRONG_BITS = 70;
/** People pick predictable characters; count roughly 60% of the raw pool entropy. */
const HUMAN_FACTOR = 0.6;

const COMMON = [
  'password', 'passw0rd', 'qwerty', 'letmein', 'welcome', 'admin', 'iloveyou', 'monkey', 'dragon', 'sunshine',
  'princess', 'football', 'baseball', 'master', 'shadow', 'superman', 'trustno1', 'whatever', 'freedom', 'lock',
  'memories', 'secret', 'changeme', 'abc123', '123456', '654321', '111111', '000000', 'login', 'family',
];
const KEYBOARD_ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm', '1234567890'];
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz';

function poolSize(s: string): number {
  let pool = 0;
  if (/[a-z]/.test(s)) pool += 26;
  if (/[A-Z]/.test(s)) pool += 26;
  if (/[0-9]/.test(s)) pool += 10;
  if (/[^a-zA-Z0-9]/.test(s)) pool += 33;
  return Math.max(pool, 1);
}

/** Characters that sit in a run of the same character, an ascending/descending sequence, or a keyboard walk. */
function patternChars(lower: string): number {
  let count = 0;
  for (let i = 2; i < lower.length; i++) {
    const [a, b, c] = [lower[i - 2], lower[i - 1], lower[i]];
    const repeat = a === b && b === c;
    const seq = [ALPHABET, '0123456789', ...KEYBOARD_ROWS].some(
      (row) => row.includes(a + b + c) || row.includes(c + b + a),
    );
    if (repeat || seq) count++;
  }
  return count;
}

export function passphraseStrength(passphrase: string, personal: string[] = []): Strength {
  const s = passphrase.normalize('NFKC');
  if (!s) return { bits: 0, level: 'weak', acceptable: false, hint: null };
  const lower = s.toLowerCase();
  const perChar = Math.log2(poolSize(s)) * HUMAN_FACTOR;
  let bits = s.length * perChar;
  let hint: string | null = null;

  const patterned = patternChars(lower);
  if (patterned > 0) {
    bits -= patterned * perChar;
    hint = 'Avoid repeated characters, sequences like “abc” or “123”, and keyboard rows.';
  }
  for (const word of COMMON) {
    if (lower.includes(word)) {
      bits -= word.length * perChar * 0.8;
      hint = 'Avoid common passwords and words like “password”.';
    }
  }
  if (/(19|20)\d\d/.test(s)) {
    bits -= 4 * perChar * 0.7;
    hint ??= 'Years are easy to guess.';
  }
  for (const p of personal.map((x) => x.trim().toLowerCase()).filter((x) => x.length >= 3)) {
    if (lower.includes(p)) {
      bits -= p.length * perChar;
      hint = 'Don’t use your name or email.';
    }
  }
  // Mostly one character class and short: the most common weak shape.
  if (s.length < 16 && poolSize(s) <= 26) hint ??= 'Longer is stronger: try four or five unrelated words.';

  bits = Math.max(0, Math.round(bits));
  const level: StrengthLevel = bits >= STRONG_BITS ? 'strong' : bits >= MIN_BITS ? 'fair' : 'weak';
  return {
    bits,
    level,
    acceptable: bits >= MIN_BITS,
    hint: level === 'strong' ? null : (hint ?? 'Add another word or two.'),
  };
}
