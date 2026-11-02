/**
 * On-device search for Sealed Vaults (roadmap 3.6f). A port of the server's blind index
 * (backend search/SearchTokenizer.java, decisions §21): the same normalisation, terms and HMAC, but run
 * on the device with a key the server never has. The server stores and matches 16-byte tokens only.
 */
import './cryptoRandom';
import { hmac } from '@noble/hashes/hmac';
import { sha256 } from '@noble/hashes/sha2';

import { toBase64 } from './sealed';

const MIN_PREFIX = 3;
const MAX_WORD = 40;
const TOKEN_BYTES = 16;
const encoder = new TextEncoder();

/** Same derivation as the server's ContentCipher.searchKey: never equal to the vault key itself. */
export function searchKey(vaultKey: Uint8Array): Uint8Array {
  return hmac(sha256, vaultKey, encoder.encode('lock:search-index:v1'));
}

/** Lower-cased, accents stripped, split on anything that is not a letter or digit, de-duplicated. */
export function words(text: string | null | undefined): string[] {
  if (!text || !text.trim()) return [];
  const folded = text.normalize('NFKD').replace(/\p{M}+/gu, '').toLowerCase();
  const out = new Set<string>();
  for (const w of folded.split(/[^\p{L}\p{N}]+/u)) {
    if (w) out.add(w.length > MAX_WORD ? w.slice(0, MAX_WORD) : w);
  }
  return [...out];
}

export function indexTerms(text: string | null | undefined): string[] {
  const terms = new Set<string>();
  for (const w of words(text)) {
    terms.add(`w:${w}`);
    for (let n = MIN_PREFIX; n < w.length; n++) terms.add(`p:${w.slice(0, n)}`);
  }
  return [...terms];
}

export function queryTerms(word: string): string[] {
  return word.length < MIN_PREFIX ? [`w:${word}`] : [`w:${word}`, `p:${word}`];
}

export function blind(key: Uint8Array, term: string): string {
  return toBase64(hmac(sha256, key, encoder.encode(term)).subarray(0, TOKEN_BYTES));
}

/** Tokens to store with a memory: every term of every field, under the key it is encrypted with. */
export function memoryTokens(vaultKey: Uint8Array, ...fields: (string | null | undefined)[]): string[] {
  const key = searchKey(vaultKey);
  const terms = new Set<string>();
  fields.forEach((f) => indexTerms(f).forEach((t) => terms.add(t)));
  return [...terms].map((t) => blind(key, t));
}

/** Per query word, its candidate tokens under every key version this device holds. */
export function queryTokens(vaultKeys: Uint8Array[], query: string): string[][] {
  const keys = vaultKeys.map(searchKey);
  return words(query)
    .slice(0, 20)
    .map((w) => keys.flatMap((k) => queryTerms(w).map((t) => blind(k, t))));
}
