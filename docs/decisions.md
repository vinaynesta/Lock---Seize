# MVP decisions

These are the starting choices for the MVP. The design doc (§47) says the technology can change
but the domain invariants should not, so each choice sits behind a seam that makes it easy to
swap later.

## Stack

| Concern | Choice now | Why | How to change later |
|---|---|---|---|
| Backend | Java 21 + Spring Boot 4.1, **modular monolith** | Design doc §45.1 recommends it. Strong transactions and simple deployment. | Packages (`identity`, `vault`, `memory`, `media`, …) only talk through services, so they can be split into separate services. |
| Database | PostgreSQL 17 + Flyway | Relational data, transactions, triggers for immutability (§45.2). | — |
| Object storage | S3-compatible (MinIO locally) | Works with AWS S3, Cloudflare R2 (no egress fees) and Backblaze B2. | `ObjectStorage` interface |
| Queue | **Transactional outbox** table drained by an in-process dispatcher | No extra infrastructure for the MVP, and events are never lost or phantom. | Swap the `OutboxDispatcher` loop for a Kafka/SQS producer. Producers stay the same. |
| Auth | Self-issued HS256 JWT (Spring Security resource server) | Simplest working option. | `TokenService` plus the resource-server config. Move to an OIDC provider (Auth0/Cognito/Keycloak) without changing the controllers. |
| Cache / search | None yet: PostgreSQL indexes | §32: database queries are enough at first. | Add Redis or a search index as derived data. |
| Client | Not started | The API comes first. | React Native / Flutter / web |

## Product and security decisions (answers to design doc §52)

1. **Encryption model: server-side envelope encryption** (feasibility recommendation).
   - Each vault has a random AES-256 data key, wrapped by a key-encryption key (KEK).
   - Memory text and reflections are AES-GCM encrypted. The associated data is `recordId:field`, so a ciphertext copied elsewhere won't decrypt.
   - The KEK is either a configuration value (`LocalKeyEncryptionService`, for development) or kept in AWS KMS (`AwsKmsKeyEncryptionService`, for production). Each vault records which KEK wrapped its key (`kek_id`). Rotating the KEK re-wraps only the vault keys, never memory content (roadmap 1.4).
   - True end-to-end encryption ("Sealed Vault") is a later premium tier.
2. **Password changes never re-encrypt content.** Vault keys are wrapped by the KEK, not the password (invariant 4).
3. **Lock has a cooling-off window.** `DRAFT → SEALING → LOCKED`.
   - While SEALING, the content is already frozen and hashed, and the creator can cancel back to DRAFT.
   - After the window (default 24h), the scheduler makes it permanently LOCKED.
4. **Locked content is enforced at three levels:**
   - the domain state machine,
   - **PostgreSQL triggers** that reject UPDATE/DELETE of locked content, even from a direct SQL client,
   - S3 Object Lock in **governance** mode (not compliance), so an administrative removal path still exists for legal erasure and illegal content.
5. **"Protected from casual deletion", not "impossible to delete."**
   - Locked memories can only be removed through a deletion request.
   - The request needs re-authentication and waits a 30-day grace period, during which it can be cancelled.
   - Only then does the purge job remove the memory. It uses a transaction-local trigger bypass that exists only on that code path.
   - The purge stays on the audit trail.
6. **Append-only reflections.** Dated notes can be added to locked memories; the original is never edited ("immutable core + mutable context").
7. **Drafts** are private to their creator and can be discarded freely.
8. **Shared vaults:**
   - Roles are OWNER and MEMBER. Only the owner invites or removes members.
   - Only a memory's creator can lock, archive or request deletion of it. Any member can read locked memories and add reflections.
   - **Breakup protocol:** anyone can leave, contributions stay, nobody can delete anyone else's memories, and a vault with no active members goes DORMANT.
   - Removing a member revokes future access only (§22). Since roadmap 2.4 it also rotates the vault key (see 15).
9. **Time capsules.** An optional `revealAt` date hides content (and media URLs) until that date, including from the author.
10. **Proof of preservation.** At sealing time a SHA-256 is computed over the stored ciphertext plus every asset's checksum. `/integrity` recomputes it and re-checks every stored object.
11. **Media** is uploaded straight to storage with a presigned PUT that has the declared SHA-256 signed into it, so storage rejects bytes that don't match. The API verifies size and checksum again on `complete`. Abandoned uploads expire after 24h.
12. **Non-members get 404, not 403,** so vault and memory ids can't be probed.
13. **Export anytime**, as JSON (`GET /vaults/{id}/export`) or as a ZIP with the original media (roadmap 2.3).
    The ZIP is built asynchronously, holds decrypted content, is downloadable only by its requester while
    they are still a member, and is deleted after 7 days.
14. **Account deletion follows Policy C** (design doc §41, decided 2026-10-23, roadmap 2.5).
    - Requested with the password; it runs after a 30-day grace period, during which it can be cancelled.
    - Personal vaults are purged with everything in them, locked memories included.
    - In shared vaults the user leaves (breakup protocol). Their drafts are discarded; their locked
      memories and reflections stay for the other members, credited to "Former member".
    - The user row is anonymised (email, name, unusable password), not deleted, because shared memories
      and the append-only audit trail reference it. The email can be registered again.
    - **Legal follow-up:** confirm with counsel that keeping locked shared contributions satisfies
      erasure requests (GDPR Art. 17 exceptions, other members' interests). The design makes Policy A
      a small change: also purge `memories.created_by = user` in shared vaults.
15. **Vault-key rotation keeps old content on its old key** (roadmap 2.4). Keys are versioned
    (`vault_keys`). Removing an active member, or a member leaving, creates a new version that all new
    content uses; drafts move to it when next edited. Locked memories and existing reflections keep the
    version they were written with, because re-encrypting them would break the immutability triggers
    and the content hash. Consequence: a removed member who kept an old key can still decrypt *old*
    content they obtain, which they could already read anyway; they cannot decrypt anything new.
16. **Uploads are scanned for malware before a memory can lock** (roadmap 2.2). A verified upload is
    `PENDING_SCAN` until the scanner (ClamAV in production) says it is clean. Infected files are
    `REJECTED` and moved to `quarantine/`, and the owner must remove them before locking. Assets of
    memories locked before 2.2 were never scanned and, being immutable, stay as they are.
17. **Thumbnails and renditions** (roadmaps 2.1, 2.8). JPEG derivatives (256 and 1024 px) are made with
    the JDK's ImageIO. They carry no EXIF (no GPS), are upright, are not part of the integrity proof and
    are never object-locked. With `ffmpeg` configured, HEIC/HEIF/WebP photos are decoded through it (F7),
    videos get a PLAYBACK MP4 (H.264/AAC, ≤ 1280 px, faststart) and stills, and audio gets an AAC MP4.
    Progressive MP4, not HLS: one file plays everywhere and adaptive bitrate isn't worth it at these
    sizes. Renditions drop all metadata. A file ffmpeg can't read still locks; it plays as uploaded.
18. **Password reset by email** (roadmap 2.6). The request endpoint always answers 202, so it can't
    reveal which emails have accounts. Tokens are single-use, valid 30 minutes, only the newest works,
    stored as SHA-256; the raw token is `HMAC(server secret, token id)` so it is never written to the
    outbox or logs. Using one revokes every session. Content is unaffected (§2). A Sealed Vault (3.6)
    will not be recoverable this way; see `sealed-vault-design.md`.
19. **Push notifications never carry content** (roadmap 2.7). Titles and bodies are generic, and
    `data` holds only ids. Everything is queued in `notifications` first (dedupe keys make enqueueing
    idempotent; scheduled ones have a `send_at`). Deletion reminders (7 and 1 days) are a safety
    feature and cannot be turned off; all other categories can.
20. **Two-step verification is TOTP plus recovery codes** (roadmap 3.1). No SMS (SIM-swap risk). The
    secret is wrapped by the KEK. Once on, it is required to sign in, to request any deletion and to
    export. The login step hands out an HMAC ticket, not a JWT, so it can never be used as an access
    token.
21. **Search uses a per-vault blind index** (roadmap 3.4). Plaintext search would require keeping
    plaintext, which server-side encryption exists to avoid. Each normalised word and each 3+ letter
    prefix is stored as a truncated HMAC under a key derived from the vault key version. Without that
    key the table reveals no words, and the same word gives different tokens in different vaults. It
    does reveal, within one vault, how many memories share a token and how long they are in words: an
    accepted trade-off for a feature users expect. Not supported: phrase search, fuzzy matching,
    ranking. A Sealed Vault would build the same index on the device.
22. **Storage quotas count original media only** (roadmap 3.2), charged to the memory's creator,
    in-flight uploads included. Being over quota only blocks new uploads; nothing preserved is touched.
23. **Cold tier by tag and lifecycle rule** (roadmap 3.3), not by copying objects: an S3 self-copy
    would create a new, unlocked version and leave the locked one hot. Only originals of memories
    locked for over a year go cold, to an instant-retrieval class, so Relive is never slow.

## Still open

- Legal confirmation of Policy C for erasure requests (see 14).
- Recovery model for when the Sealed Vault (E2E) tier arrives. Options and a recommendation (recovery
  phrase + device approval) are in [`sealed-vault-design.md`](sealed-vault-design.md).
- ~~Vault-key rotation on member removal~~ Done (15).
- ~~Media processing workers~~ Done: thumbnails, malware scanning, transcoding and HEIC/WebP (16, 17).
- ~~MFA~~ Done (20). (Rate limiting is done, in memory per instance, see roadmap 1.2. Refresh tokens are done: 15-minute access tokens plus rotating 30-day refresh tokens with reuse detection; see `docs/roadmap.md` 1.1.)
- ~~Storage limits per plan, cold-tier archival~~ Done (22, 23). Billing (how a user changes plan) is not built.
- ~~Scheduler locking~~ Done: jobs claim work with `FOR UPDATE SKIP LOCKED` (roadmap 1.3).
