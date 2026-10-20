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
   - Removing a member revokes future access only (§22). Vault-key rotation on removal is a follow-up.
9. **Time capsules.** An optional `revealAt` date hides content (and media URLs) until that date, including from the author.
10. **Proof of preservation.** At sealing time a SHA-256 is computed over the stored ciphertext plus every asset's checksum. `/integrity` recomputes it and re-checks every stored object.
11. **Media** is uploaded straight to storage with a presigned PUT that has the declared SHA-256 signed into it, so storage rejects bytes that don't match. The API verifies size and checksum again on `complete`. Abandoned uploads expire after 24h.
12. **Non-members get 404, not 403,** so vault and memory ids can't be probed.
13. **Export anytime** as JSON. A bundled ZIP with original media is a follow-up worker job.

## Still open

- Account deletion policy (design doc §41: personal vs shared vaults). Needs a legal decision.
- Recovery model for when the Sealed Vault (E2E) tier arrives.
- Vault-key rotation on member removal.
- Media processing workers: thumbnails, transcoding, malware scanning (plug into the outbox).
- MFA. (Rate limiting is done, in memory per instance, see roadmap 1.2. Refresh tokens are done: 15-minute access tokens plus rotating 30-day refresh tokens with reuse detection; see `docs/roadmap.md` 1.1.)
- Storage limits per plan, cold-tier archival.
- ~~Scheduler locking~~ Done: jobs claim work with `FOR UPDATE SKIP LOCKED` (roadmap 1.3).
