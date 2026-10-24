# Sealed Vault (end-to-end encryption): design note

Roadmap 3.6 · Status: **proposal, waiting for the recovery-model decision** (Product + Security)
· Written 2026-10-24

## 1. What it is

A premium vault tier where the server **never** has the key. Today every vault is encrypted
server-side (decisions §1): the vault data key is wrapped by a KEK the server (or AWS KMS) holds, so
Lock can make thumbnails, scan for malware, search, build exports, and recover a forgotten password.
A Sealed Vault trades those conveniences for a guarantee that nobody at Lock, and nobody who breaches
Lock, can read its content.

It is opt-in per vault, chosen at creation. A vault never switches between modes: converting would
mean re-encrypting locked content, which the immutability rules forbid (decisions §4, §15).

## 2. The decision that blocks it: recovery

With end-to-end encryption, "forgot my password" can no longer be solved by the server (design §21).
One of these has to be chosen before any code is written:

| | A. Recovery key (user-held) | B. Trusted-device escrow | C. Social recovery (k-of-n) | D. No recovery |
|---|---|---|---|---|
| How | At setup the app shows a 24-word recovery phrase. It can unwrap the user's key bundle. | Every signed-in device holds the key. A new device is approved by an existing one (QR code). | The key is split (Shamir) between 3–5 people the user trusts; any k of them can restore it. | Lose the password and every device: the vault is gone. |
| Server sees | Nothing usable | Nothing usable | Nothing usable | Nothing usable |
| Risk of permanent loss | Medium: people lose phrases | Medium: lose all devices at once | Low, if the contacts stay in touch | High |
| UX cost | One "write this down" step | Approving each new device | Setup asks for contacts; recovery needs their help | None up front, harsh later |
| Build effort | S | M | L | — |
| Fits "some memories deserve to be permanent" | Yes, with a loud warning | Yes | Best, but complex | Contradicts it |

**Recommendation: A + B together.** A recovery phrase as the safety net, plus device-to-device
approval as the everyday path, so the phrase is almost never needed. This is the model 1Password,
Signal backups and WhatsApp E2E backups have taught users. C can come later on the same foundation
(a Shamir share is just another wrapper of the same key). D should not ship: a product about
permanence must not make loss this easy.

**Password reset (2.6) and Sealed Vaults:** a reset gives the account back but **not** a Sealed
Vault's content. The user must then use the phrase or an existing device. The reset email and screen
have to say so.

## 3. Cryptographic design (assuming A + B)

```
user secret key (X25519, generated on device)
   ├── wrapped by a key derived from the password (Argon2id)  → stored on the server (opaque)
   ├── wrapped by the recovery phrase (Argon2id)               → stored on the server (opaque)
   └── held in each device's secure enclave / keystore
vault data key (AES-256, generated on the creating device)
   └── wrapped to each member's public key (X25519 + HKDF + AES-GCM)  → vault_member_keys
content: AES-256-GCM with the vault data key, AAD = recordId:field   (same scheme as today)
media:   encrypted on device before upload; storage holds ciphertext only
```

- **Library:** libsodium (`react-native-libsodium`, `libsodium-wrappers` on web). No custom crypto.
- **Versioned vault keys** (2.4) carry over unchanged: removing a member makes the remaining
  members' devices create version n+1 and wrap it to the remaining members only.
- **Content hash / proof of preservation:** computed by the client over the ciphertext and the
  ciphertext checksums. The server can still re-check that stored bytes match the recorded checksums
  (integrity of storage), but not that the plaintext is what the user wrote. That check moves to the
  client ("Verify on this device").
- **Immutability triggers:** unchanged. They act on ciphertext columns, which is what they do today.

### Schema sketch (new migration, nothing existing changes)

```sql
ALTER TABLE vaults ADD COLUMN encryption VARCHAR(20) NOT NULL DEFAULT 'SERVER'
    CHECK (encryption IN ('SERVER', 'SEALED'));
CREATE TABLE user_key_bundles (user_id UUID PRIMARY KEY, public_key BYTEA NOT NULL,
    wrapped_by_password BYTEA NOT NULL, wrapped_by_recovery BYTEA, kdf_params JSONB NOT NULL, ...);
CREATE TABLE vault_member_keys (vault_id UUID, user_id UUID, key_version INT,
    wrapped_key BYTEA NOT NULL, PRIMARY KEY (vault_id, user_id, key_version));
CREATE TABLE device_approvals (...);   -- short-lived, for approving a new device
```

## 4. What degrades in a Sealed Vault

| Feature | Server-side vault | Sealed Vault |
|---|---|---|
| Thumbnails, playback renditions (2.1, 2.8) | Server | Made **on device** before upload and uploaded as encrypted derivatives |
| Malware scanning (2.2) | Server scans | **Not possible** on ciphertext. Scan on device where the OS allows; otherwise accept the risk, which only affects the vault's own members |
| Search (3.4) | Server blind index | Blind index built **on device** with a key derived from the vault key. Same server table, same query path |
| ZIP export (2.3) | Server builds | Built **on device** (the device has the key); slower, needs the app open |
| Push notification text (2.7) | Generic already | Unchanged |
| Password reset (2.6) | Restores everything | Restores the account only; content needs the phrase or a device |
| Cold tier (3.3) | Works | Works (opaque bytes) |
| Account deletion (2.5) | Works | Works; deleting the wrapped keys makes the content unreadable even before the purge |
| Web client | Full | Needs WebCrypto + IndexedDB key storage; recommend native-only at first |

## 5. Effort (one developer, A + B)

| Piece | Size |
|---|---|
| Key bundle, Argon2id, recovery phrase (client + API) | M |
| Vault key wrapping per member, rotation on removal | M |
| Client-side content encryption (reuse field/AAD scheme) | S |
| Client-side media encryption + on-device thumbnails/renditions | L |
| Device approval flow (QR) | M |
| On-device search index and export | M |
| Security review + external audit of the crypto | M (plus vendor time) |
| **Total** | **XL: about 8–10 weeks**, split into the rows above |

## 6. Decisions needed

1. Recovery model (§2). Recommendation: A + B.
2. Malware policy for Sealed Vaults (§4): accept unscanned media inside a sealed vault, or allow only
   photos/videos re-encoded on device?
3. Web support at launch: native-only (recommended) or also web?
4. Pricing tier and whether shared Sealed Vaults ship at launch or after personal ones.

Once (1) is decided, record it in `decisions.md` and split 3.6 into the rows of §5 in the roadmap.
