# Sealed Vault: internal security review (roadmap 3.6g, part 1)

_Reviewed 2026-11-08 against branch `feat/two-step-verification/storage-quotas`. Read-only review of the
code, followed by two rounds of fixes the same day (the second added format v2: authenticated key wraps,
key pinning, and bound reflections and media). This is the internal half of 3.6g; the external audit by
a crypto vendor is still required before any paid launch._

**Threat model.** A malicious or compromised server (including its database), and an attacker holding a
stolen access token. Out of scope: a compromised, unlocked device; side channels inside `@noble/*`.

**Scope.** Client: `client/src/lib/sealed*.ts`, `deviceApproval.ts`, `cryptoRandom.ts`, `useSealed.ts`,
`tokenStore.ts`, `app/sealed-keys.tsx`, `app/approve-device.tsx`. Server: `crypto/E2eFormats.java`, key
bundle, vault member key and device approval services and controllers, the sealed paths in memory,
media, export and asset processing, `SecurityConfig`, migrations V16 and V17.

## Summary

| ID | Severity | Finding | Status |
|---|---|---|---|
| F1 | High | The server is the key directory; nothing on the device verifies a member's public key | ✅ Fixed: keys pinned on first use, changed keys refused until accepted, in-person verification |
| F2 | High | Wrapped vault keys don't authenticate the wrapper, so the server can plant a vault key it knows | ✅ Fixed: authenticated wrap format v2 |
| F3 | Medium | The Sealed Vault passphrase could equal the account password, which the server sees | ✅ Fixed |
| F4 | Medium | Decrypted media and partial export ZIPs could stay in the cache | ✅ Fixed |
| F5 | Low | Reflection AAD doesn't bind the memory or author | ✅ Fixed (new reflections) |
| F6 | Low | Media file keys aren't bound to the asset id | ✅ Fixed (new uploads, media format v2) |
| F7 | Low | The streaming decryptor accepted a header-only (fully truncated) file | ✅ Fixed |
| F8 | Low | The recovery phrase stayed on the clipboard indefinitely | ✅ Fixed |
| F9 | Low | Wrapped secret key is served to any valid token; Argon2id at the OWASP floor | ◐ Strength check ✅ (≥ 50 bits, on the device); a higher Argon2 cost stays a product call |
| I1 | Info | Server-supplied Argon2 parameters were used as-is | ✅ Fixed |
| I2 | Info | `encryptFile` trusted the file size and could drop data | ✅ Fixed |
| I3 | Info | Blind search tokens leak shared words and prefixes (decisions §21) | Accepted, documented |
| I4 | Info | The vault key is used directly for content, media HKDF and the search HMAC | Open: separate subkeys (a later format revision) |
| I5 | Info | Session tokens used the default Keychain accessibility | ✅ Fixed |
| I6 | Info | `Math.random` picks the phrase-check positions | Accepted: UX only, not a secret |

## Findings

### F1 · High · The server controls the key directory (fixed)
`wrapFor` wrapped new vault keys to whatever `members[].publicKey` the server returned, and the screen
showed the server's fingerprint text.

**Attack.** The server substitutes its own X25519 key for a member's (or adds a ghost member), then bumps
the key version. The next device to open the vault wraps the new key to the server.

**Fix (`client/src/lib/keyPins.ts`).**
- Each device pins every member's public key the first time it sees it (trust on first use), per account.
- A different key later is reported as changed. The device won't wrap a vault key to it, won't accept
  a wrap verified with it, and won't create a new key version while it is unresolved.
- Members shows each fingerprint as computed on the device. A changed key shows the old and new
  fingerprints and an "accept the new key" action, which needs a confirmation. After an in-person
  comparison a member can be marked "Verified in person".
- The device refuses to share keys if the server lists a different key for the user's own account.

**Remaining limit.** Trust on first use: a key swapped before a device first sees a member isn't
detected unless members compare fingerprints in person. That's why the verify action exists.

### F2 · High · Unauthenticated key wraps (fixed)
`wrapVaultKey` was an anonymous sealed box, and devices accepted any copy that unwrapped. A compromised
server could insert a vault key it chose, wrapped to members' real public keys.

**Fix: format v2 (`wrapVaultKeyAuth`, `E2eFormats.wrappedVaultKey`).**
- HPKE Auth-mode style: HKDF over `X25519(eph, R) || X25519(senderStatic, R)`, with salt
  `eph || R || S` and info `lock:vault-key:v2:<vault>:<version>:<recipient>:<sender>`. Only the holder of
  the claimed sender's secret key can produce a copy that opens.
- The server refuses new version-1 uploads (`LEGACY_KEY_FORMAT`). It stores the wrapper's public key on
  the row (`V19`), so a copy stays verifiable after the wrapper deletes their account. The device checks
  that stored key against its pin (F1).
- Version-1 copies stored before the upgrade still open, but only up to the key version the device
  first saw for that vault (`acceptLegacyWrap`). A version-1 copy inserted for a later version is ignored.
- Device approval keeps the anonymous box: its request key is checked against the QR fingerprint, not
  the server.

### F3 · Medium · Passphrase equal to the account password (fixed)
The server sees the account password at every sign-in. If it was reused as the passphrase, the server
could run Argon2id on the bundle it stores. **Fix:** `requireDistinctPassphrase` refuses it (after NFKC
normalisation) in setup, recovery and passphrase change. Test: `src/lib/__tests__/sealedGuards.test.ts`.

### F4 · Medium · Plaintext left in the cache (fixed)
Decrypted copies were tracked only in memory and cleared only at sign-out, so a crash or a killed app
left them behind. A failed export left its partial ZIP, and the export also put a decrypted copy of every
original in the viewing cache. **Fix:**
- `sweepSealedCache()` deletes every `sealed-*` and `lock-export-*` file at launch and at sign-out.
- The export streams each file from download to ZIP and writes no plaintext copy.
- A failed export deletes its ZIP.
- The player deletes the decrypted video or audio when it closes.

### F5 · Low · Reflections not bound to memory or author (fixed)
AAD was `reflectionId:reflection`, so the server could show a reflection under another memory, or with
another author. **Fix:** new reflections use record `memoryId:reflectionId` and field
`reflection:v2:<authorId>` (`reflectionAad`). Reflections written earlier still open with the old AAD,
without the binding.

### F6 · Low · Media keys not bound to the asset (fixed)
The file-key info was `lock:media:v1:<purpose>:<memoryId>`, so two files of one memory could be swapped.
**Fix:**
- Media format v2 (header version byte 2) uses info `lock:media:v2:<purpose>:<memoryId>:<assetId>`.
- The device picks the asset id before encrypting. `POST /memories/{id}/assets/upload` takes an optional
  `id`, for sealed vaults only.
- Thumbnails are bound the same way. Version-1 files still open, without the binding.

### F7 · Low · Header-only file accepted (fixed)
`decryptFile` didn't check `finished`, so a file truncated to its 16-byte header decrypted to an empty
file. **Fix:** both streaming paths (`decryptFile`, `streamSealedAsset`) throw `This file is truncated`.

### F8 · Low · Recovery phrase on the clipboard (fixed)
**Fix:** the copy button clears the clipboard after 60 seconds if it still holds the phrase.

### F9 · Low · Offline-guessing target (open)
`GET /account/key-bundle` returns the wrapped secret key to any valid token. Argon2id runs at m=19 MiB,
t=2 (the OWASP floor, chosen because plain-JS Argon2 took 3 s at 64 MiB). With F3 fixed, the remaining
gap is passphrase strength. **Done (2026-11-08):** a strength estimator on the device (`passphraseStrength.ts`)
with a live meter. It refuses passphrases under 50 bits (patterns, common words, years, the account's
name or email all count against them) in setup, recovery and change. **Still open:** a native Argon2 at
64 MiB/t=3 (unlock time is a product call), or re-authentication before the bundle is served to a new
device.

### Info
- **I1 (fixed):** the device now refuses Argon2 parameters outside the server's range (m 19456–262144
  KiB, t 2–10, p 1–4) instead of running whatever the server sends. Test in `sealed.test.ts`.
- **I2 (fixed):** `encryptFile` reads one chunk ahead to find the last chunk instead of trusting `size`.
- **I3 (accepted):** deterministic blind tokens tell the server which memories share words or 3+ letter
  prefixes, roughly how much text each has, and query patterns. Documented in decisions §21.
- **I4 (open):** derive separate content, media and search subkeys with HKDF in format v2.
- **I5 (fixed):** session tokens are stored `WHEN_UNLOCKED_THIS_DEVICE_ONLY`.

## Checked and found sound
- **Randomness:** every nonce, salt and key comes from `@noble` `randomBytes` on expo-crypto's CSPRNG, and
  `cryptoRandom` is imported first. noble throws rather than falling back to weaker randomness.
- **Nonces:** random 96-bit nonces for content, secret wraps and key wraps. Media derives a per-file key
  from an 88-bit salt and uses counter nonces, so no (key, nonce) pair repeats.
- **Chunked media:** the header is the AAD; each nonce carries the chunk index and a last-chunk flag.
  Reordering, dropping chunks, truncating, appending data and editing the header all fail.
- **Content AAD:** `recordId:field` prevents swaps between fields and records. Vaults and versions use
  separate random keys, so swapping between them can only make decryption fail.
- **Sealed box:** the HKDF salt is the ephemeral key followed by the recipient key; info and AAD bind the
  vault, version and recipient. noble 1.9.7 rejects an all-zero shared secret. Device-approval wraps are
  domain-separated.
- **Recovery:** the BIP-39 phrase carries 256 bits of entropy and goes through HKDF with its own salt and
  info. The passphrase and recovery wraps use different AAD. Unwrapped keys are checked against the
  account public key.
- **Device approval:** the full 128-bit fingerprint from the QR is checked against the server's copy;
  requests are account-scoped, expire in 10 minutes, are collected only once, and at most 5 can be live.
- **Key storage:** SecureStore `WHEN_UNLOCKED_THIS_DEVICE_ONLY`. A key belonging to another account is
  deleted. Keys are wiped on sign-out and on a 401.
- **Server:** sealed endpoints return 404 to non-members. Plaintext, server search and server export are
  refused in sealed vaults. Writes need the current key version. Triggers make public keys and wrapped
  keys immutable. Creating a bundle or changing the passphrase needs the password plus MFA.
- **Logging:** no keys, plaintext or tokens are logged on either side.

## For the external audit
Review format v2 as one revision: the authenticated wraps (F2), the pinning rules and their
trust-on-first-use window (F1), the legacy-acceptance rules for version-1 wraps, reflections and media,
and the bindings in F5 and F6. Still open for the auditor's opinion: I4 (separate subkeys) and F9
(passphrase strength and Argon2 cost).

**Tests for format v2:**
- `sealed.test.ts`: authenticated wraps, including a server-planted key that is refused.
- `keyPins.test.ts`: first-use pinning, changed keys, the legacy ceiling, pins kept per account.
- `sealedBindings.test.ts`: media and reflection bindings, and legacy files and reflections that still
  open.
- Server: `newWrapsMustBeAuthenticated_andCarryTheWrappersPublicKey` and
  `sealedUploadsMayChooseTheirAssetId_serverVaultsMayNot`.
