# Sealed Vault protocol specification

_Status: format v2, as built 2026-11-08. This document is for the external crypto audit (roadmap 3.6g):
it states every byte format, derivation and acceptance rule the client and server implement, so the
auditor can check the code against a fixed reference. Design rationale: [`sealed-vault-design.md`](sealed-vault-design.md).
Internal review and its fixes: [`security-review-sealed-vault.md`](security-review-sealed-vault.md)._

**Code.**
- Client: `client/src/lib/sealed.ts` (keys, wraps, content), `sealedMedia.ts`, `sealedSearch.ts`,
  `deviceApproval.ts`, `keyPins.ts`, `sealedVault.ts` (the rules in §8).
- Server shape checks: `backend/src/main/java/app/lock/crypto/E2eFormats.java`.

**Primitives** (pinned versions, audited libraries only: `@noble/ciphers` 1.3.0, `@noble/curves` 1.9.7,
`@noble/hashes` 1.8.0, `@scure/bip39` 1.6.0):
- X25519
- AES-256-GCM (12-byte nonce, 16-byte tag)
- HKDF-SHA256
- HMAC-SHA256
- Argon2id
- BIP-39 (English)

Randomness is noble's `randomBytes`, backed by `expo-crypto` (OS CSPRNG) on native.
`cryptoRandom.ts` is imported before any noble module, and noble throws rather than falling back.

**Notation.**
- `||` is concatenation.
- Strings are UTF-8.
- Ids are lower-case UUID strings.
- `b64` is standard base64 with padding.

## 1. Threat model

| Party | Assumed able to |
|---|---|
| Server / database | Read and rewrite everything it stores; serve any response; see the account password at sign-in; collude with a member |
| Stolen access token | Call any API as the user until the token expires |
| Other member | Anything a member device can do |

**Goals.**
1. The server never learns a vault key, any content, or any media.
2. Ciphertext can't be moved between records, fields, memories, assets, vaults or key versions without
   detection.
3. The server can't get a device to encrypt under a key the server knows.

**Out of scope.**
- A compromised, unlocked device.
- Traffic analysis.
- Metadata such as sizes, timestamps and membership.
- The leakage of the blind index (§6), which is accepted (decisions §21).

## 2. Account key pair and its wraps

| Item | Definition |
|---|---|
| Account key pair | X25519 `(sk, pk)`, `sk` = 32 random bytes; generated once, never rotated (`pk` immutable server-side by trigger) |
| Fingerprint | first 16 bytes of `SHA-256(pk)`, hex, groups of 4 (`fingerprint()`); computed on the device, never taken from the server |
| Passphrase key | `Argon2id(NFKC(passphrase), salt16, m=19456 KiB, t=2, p=1, dkLen=32)`; the device refuses parameters outside m 19456–262144, t 2–10, p 1–4 |
| Recovery phrase | 24 BIP-39 words = 256 bits of entropy |
| Recovery key | `HKDF-SHA256(ikm = BIP-39 entropy, salt = salt16, info = "lock:recovery-wrap:v1", 32)` |
| Secret wrap (61 B) | `0x01 \|\| nonce12 \|\| AES-GCM(wrapKey, nonce, aad = "lock:secret-key:<purpose>:v1", sk)`, purpose ∈ {`passphrase`, `recovery`} |

After unwrapping, the device checks `X25519.pub(sk) == bundle.pk` (`checkPublicKey`). The passphrase
must differ from the account password (NFKC-compared) and pass the strength estimator
(`passphraseStrength.ts`, at least 50 bits). Both checks run on the device in setup, recovery and change.

Device storage:
- `sk` lives in SecureStore `lock.sealed.secretKey` (`WHEN_UNLOCKED_THIS_DEVICE_ONLY`).
- Vault keys stay in memory only.
- Both are wiped on sign-out and on a 401.

## 3. Vault keys

A vault key `K_v` is 32 random bytes, one per key version `v`. The server bumps the version when a member
leaves or is removed. The first member device to open the vault afterwards generates the new key and
must wrap it for every active member in a single request.

### 3.1 Authenticated wrap (format 2, current)

```
eph            = random X25519 key pair
ikm            = X25519(eph.sk, R.pk) || X25519(S.sk, R.pk)          (sender S, recipient R)
salt           = eph.pk || R.pk || S.pk
info           = "lock:vault-key:v2:<vaultId>:<version>:<recipientId>:<senderId>"
key            = HKDF-SHA256(ikm, salt, info, 32)
blob (93 B)    = 0x02 || eph.pk(32) || nonce12 || AES-GCM(key, nonce, aad = info, K_v)(32+16)
```

Unwrap recomputes `ikm = X25519(R.sk, eph.pk) || X25519(R.sk, S.pk)`. A self-wrap uses `S = R`. The
server stores `S.pk` on the row (`vault_member_keys.wrapped_by_public_key`, V19), and the device checks
it against its pin for S (§8).

### 3.2 Legacy anonymous wrap (format 1, read-only)

```
key  = HKDF-SHA256(X25519(eph.sk, R.pk), salt = eph.pk || R.pk, info = "lock:vault-key:v1:<vaultId>:<version>:<recipientId>", 32)
blob = 0x01 || eph.pk || nonce12 || AES-GCM(key, nonce, aad = info, K_v)
```

The server refuses new format-1 uploads (`LEGACY_KEY_FORMAT`). Devices accept format 1 only under the
ceiling rule in §8.

## 4. Content fields

```
field ciphertext = nonce12 || AES-GCM(K_v, nonce, aad, UTF-8(plaintext))
```

| Record | AAD |
|---|---|
| Memory field (`title`, `thought`, `feeling`, `location`, `coordinates`) | `"<memoryId>:<field>"`; `coordinates` plaintext is `"<lat>,<lng>"` with 6 decimals (roadmap 5.1) |
| Reflection (format 2) | `"<memoryId>:<reflectionId>:reflection:v2:<authorId>"` |
| Reflection (legacy, read-only) | `"<reflectionId>:reflection"` |

Each memory and reflection carries the key version it was encrypted under. Writes must use the vault's
current version (`409 STALE_KEY_VERSION` otherwise). The client picks memory and reflection ids, because
the AAD needs them before the request. The device tries format 2 first, then legacy.

## 5. Media

```
header (16 B)  = "LKM1" || version(1) || salt11
file key       = HKDF-SHA256(K_v, salt = salt11, info, 32)
  version 2    : info = "lock:media:v2:<purpose>:<memoryId>:<assetId>"      purpose ∈ {original, thumb}
  version 1    : info = "lock:media:v1:<purpose>:<memoryId>"                (legacy, read-only)
chunk i        = AES-GCM(file key, nonce = 0^7 || uint32be(i) || last?1:0, aad = header, plain_i)
plain chunk    = 1 MiB (last may be shorter; an empty file is one empty last chunk)
```

Decryption fails on any reordered, dropped or appended chunk, on a header edit, or on a missing last
chunk, including a header-only file. The device picks the asset id before encrypting
(`POST /memories/{id}/assets/upload` with `id`, sealed vaults only). Thumbnails are 256 px JPEGs, made
and encrypted on the device with the same scheme.

## 6. Blind search index

```
search key  = HMAC-SHA256(K_v, "lock:search-index:v1")
words       = NFKD, strip marks, lower-case, split on non-letter/digit, de-duplicate, truncate to 40 chars
terms       = "w:<word>" for each word, plus "p:<prefix>" for every prefix of length ≥ 3 shorter than the word
token       = first 16 bytes of HMAC-SHA256(search key, term)
```

A query sends, for each word, the candidate tokens under every key version the device holds. The server
returns memories matching every word. The server learns which memories share tokens and the access
pattern (accepted, decisions §21).

## 7. Device approval (new device, no passphrase)

```
new device : one-time X25519 pair (a); POST request with a.pk; shows QR "lock-approve:v1:<requestId>:<fingerprint(a.pk)>"
approver   : scans QR; refuses unless fingerprint(server copy of a.pk) equals the QR fingerprint;
             sends wrap = format-1 box of sk to a.pk with vaultId = "device-approval", version = 1,
             recipientId = "<userId>:<requestId>"
new device : unwraps with a.sk; checks X25519.pub(sk) == account pk
```

This exchange uses the anonymous box on purpose: its recipient key is authenticated by the QR
fingerprint, not by the server. The `device-approval` label can't collide with a vault UUID. Requests
are account-scoped, expire after 10 minutes, are collected once, can be approved once, and at most 5 can
be live at a time.

## 8. Device rules (`sealedVault.ts`, `keyPins.ts`)

1. **Own key.** If the server lists a key for the user other than the device's own `pk`, the device stops
   and shares nothing.
2. **Pins.** Each member's `pk` is pinned per account on first sight (trust on first use; AsyncStorage
   `lock.keypins.v1.<accountId>`). A different key later is recorded as changed. The user resolves it in
   Members by comparing fingerprints and accepting the new key, or marks a member verified after an
   in-person check.
3. **Wrapping.** The device wraps only with format 2, only to keys whose pins match, and never to a member
   with a changed key. A new key version can't be created while any member's key change is unresolved.
4. **Unwrapping format 2.** The sender key comes from the pin. If S is unpinned, the key stored with the
   copy is pinned. A stored key that differs from the pin is refused. A copy with no sender key is
   ignored.
5. **Unwrapping format 1.** Accepted only for `version ≤ ceiling[vault]`. The ceiling is set to the
   vault's current version the first time the device opens the vault after the upgrade.
6. **Failures.** A failure on the current version blocks the vault (no writes). Failures on older versions
   only hide those versions.
7. **Offline drafts.** Only the encrypted request body (§4) is queued on the device. If the key version
   went stale, the draft is decrypted with the old key and encrypted again under the newest key held.
8. **Offline media.** A file (and a photo's thumbnail) is encrypted under the current key, bound to a
   device-chosen asset id (§5), before it is queued. Only ciphertext is written to the persistent queue
   folder. A retry reuses the same id: the server re-issues the upload URL only for the same memory, size,
   checksum and type while the asset is still `INITIATED`.

## 9. Server-side checks (`E2eFormats`, services)

- Shape checks:
  - public keys 32 bytes;
  - secret wraps 61 bytes, format 1;
  - vault-key wraps 93 bytes, format 2;
  - content at least 28 bytes, within the plaintext limits;
  - media sizes follow the chunk arithmetic.
- Argon2 parameters within range.
- Plaintext refused in sealed vaults (`PLAINTEXT_IN_SEALED_VAULT`).
- Non-members get 404 on every sealed endpoint.
- A new key version must cover every active member; only holders can share an existing version.
- `user_key_bundles.public_key` and `vault_member_keys` rows are immutable (database triggers).
- Bundle creation and passphrase change need the password plus MFA.
- The server can't verify any ciphertext; these checks only stop buggy clients storing unusable data.

## 10. Known limits for the auditor's opinion

- **Trust on first use:** a key swapped before a device first sees a member is caught only by an
  in-person fingerprint comparison.
- **Legacy data** (format-1 wraps under the ceiling, legacy reflections and media) keeps the old, weaker
  bindings.
- **I4:** `K_v` is used directly for content AES-GCM, as the media HKDF input and as the search HMAC key.
  Separate HKDF subkeys would be cleaner, but would change every format.
- **Argon2id at the OWASP floor** in plain JS (64 MiB took about 3 s). The strength estimator offsets it.
  A native Argon2 would allow higher cost.
- **No forward secrecy for vault keys:** a leaked account `sk` opens every key version wrapped to it.
- **Sealed photos open through the share sheet** (plaintext handed to the chosen app). Video and audio
  play in the app.

## 11. Test vectors

[`sealed-vault-test-vectors.json`](sealed-vault-test-vectors.json) has fixed inputs and outputs for every
format above: account keys and fingerprints, the passphrase and recovery wraps, vault-key wraps in
formats 2 and 1, a memory field, a format-2 reflection, a one-chunk and a two-chunk media file, blind
search tokens, and the device-approval box. Randomness came from a seeded generator, so every value is
reproducible.

`client/src/lib/__tests__/protocolVectors.test.ts` checks the vectors two ways:
1. **Regression.** The app's crypto, run with the same seed, reproduces the file byte for byte.
2. **Independent.** A second implementation written from this document on Node's `crypto` alone opens or
   recomputes every vector, sharing no code with the app.
   - Primitives: X25519, HKDF-SHA256, AES-256-GCM, HMAC-SHA256, and Argon2id (`crypto.argon2Sync`,
     Node 24.7+; skipped on older Node).
   - The two-chunk media vector is rebuilt from the spec and compared by SHA-256.

An auditor can check the spec the same way in any language, by opening the vectors with the keys given.
Regenerate them only after an intended format change: `UPDATE_VECTORS=1 npx jest protocolVectors`.

## 12. Tests that exercise this specification

| Area | Tests |
|---|---|
| Wraps, content, Argon2 range | `client/src/lib/__tests__/sealed.test.ts` |
| Pins, legacy ceiling | `keyPins.test.ts` |
| Media and reflection bindings, legacy compatibility | `sealedBindings.test.ts` |
| Media format, search tokens (incl. an independent HMAC check), device approval | `sealedMediaSearch.test.ts` |
| Passphrase guards and strength | `sealedGuards.test.ts`, `passphraseStrength.test.ts` |
| Every format against fixed vectors, plus an independent Node implementation | `protocolVectors.test.ts` |
| Offline encrypted drafts and media | `sealedOfflineDraft.test.ts`, `sealedUploadQueue.test.ts`, `sealedUploadRetry.test.ts` |
| Server rules | `SealedVaultIntegrationTest`, `SealedMediaDevicesSearchIntegrationTest` |
