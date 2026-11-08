# Lock architecture (as built)

This is how Lock is actually built today. The original design and its reasoning are in
[`Lock_High_Level_Detailed_System_Design.md`](Lock_High_Level_Detailed_System_Design.md); the choices
that turned the design into code are in [`decisions.md`](decisions.md) (cited as §n below); the
end-to-end encrypted tier is detailed in [`sealed-vault-design.md`](sealed-vault-design.md).

_Last updated: 2026-11-08 (roadmap through 3.11, 1.7, 4.2, 4.3, 5.1 and 5.2: Sealed format v2 and key
pinning, shared rate limits, offline sealed media, Expo SDK 58, docs CI, memory map with pin picker,
clustering and an all-vaults map)._

Diagrams are Mermaid, which GitHub renders inline. CI's `docs` job (`tools/docs-check`) parses every
diagram in `docs/` with Mermaid itself, so a diagram that wouldn't render fails the build.

---

## 1. System context

```mermaid
flowchart LR
    user([Member])
    subgraph device[Phone / browser]
        app["Lock client<br/>Expo SDK 58 / React Native 0.88<br/>(iOS, Android, web)"]
        keychain[("Keychain / Keystore<br/>tokens, sealed secret key")]
        local[("App storage<br/>offline queue (sealed: ciphertext only),<br/>pinned member keys")]
        gps["GPS"]
        app --- keychain
        app --- local
        app --- gps
    end
    subgraph platform[Lock platform]
        api["Lock API<br/>Spring Boot 4, Java 21<br/>modular monolith"]
        pg[("PostgreSQL 17<br/>metadata, outbox, audit,<br/>immutability triggers")]
        s3[("S3-compatible storage<br/>media, derivatives, exports<br/>object lock + lifecycle")]
        clamd["clamd<br/>malware scan"]
        ffmpeg["ffmpeg<br/>(in the API image)"]
    end
    kms["AWS KMS<br/>(KEK, optional)"]
    postmark["Postmark<br/>email"]
    expo["Expo push service"]
    prom["Prometheus<br/>+ alert rules"]
    maps["Apple Maps (iOS) / Google Maps (Android)<br/>map tiles + platform geocoder"]

    user --> app
    app -- "HTTPS + JWT" --> api
    app -- "presigned PUT / GET<br/>(media bytes)" --> s3
    api --> pg
    api --> s3
    api -- INSTREAM --> clamd
    api --> ffmpeg
    api -- "wrap / unwrap vault keys" --> kms
    api --> postmark
    api --> expo
    prom -- "scrape management port" --> api
    app -- "map tiles; place lookup<br/>only when the user asks" --> maps
```

Media never passes through the API: the client uploads and downloads directly with short-lived
presigned URLs that the API issues only after checking access (design §11, invariant 5). The maps
provider sees map views and, only on request, the place names being looked up; never stored
positions, which go to the API encrypted (decisions §27).

## 2. Backend modules

One deployable, split into packages that talk only through services (decisions, Stack). Each could
become its own service later; the outbox is the seam for that.

```mermaid
flowchart TB
    subgraph web[HTTP layer]
        filters["RequestIdFilter · AuthRateLimitFilter<br/>Spring Security (JWT resource server)"]
    end
    subgraph domain[Domain packages]
        identity["identity<br/>register, login, refresh tokens,<br/>password reset, MFA (TOTP),<br/>Sealed key bundles, device approval"]
        vault["vault<br/>vaults, membership, authorization,<br/>versioned vault keys, sealed member keys"]
        memory["memory<br/>state machine, cooling-off, reflections,<br/>capsules, relive, integrity, deletion,<br/>map positions (encrypted)"]
        media["media<br/>presigned uploads (sealed: client-chosen ids,<br/>idempotent retry), scan, thumbnails,<br/>renditions, cold tier, lifecycle"]
        search["search<br/>blind index"]
        export["export<br/>async ZIP"]
        account["account<br/>deletion (Policy C)"]
        notification["notification<br/>push queue, preferences"]
        quota["quota"]
        email["email"]
    end
    subgraph platform[Platform packages]
        crypto["crypto<br/>KEK (local / KMS), AES-GCM,<br/>E2E payload checks"]
        outbox["outbox<br/>publisher + dispatcher"]
        audit["audit<br/>append-only trail"]
        security["security<br/>RateLimiter: in-memory | Postgres,<br/>lockout per account + IP"]
        observability["observability"]
    end

    filters --> identity & vault & memory & media & export & account & notification & quota
    memory --> vault & search & crypto
    media --> memory & quota
    export --> memory
    account --> vault & memory & identity & export
    vault --> crypto & identity
    identity & vault & memory & media & export & account --> audit & outbox
    outbox --> media & notification & export & email
    filters & identity --> security
```

## 3. Data model

Main tables (Flyway `V1`–`V21`; V18 clears sealed search-skip rows, V19 adds `vault_member_keys.wrapped_by_public_key`, V20 adds `rate_limit_hits`, V21 adds `memories.coordinates_ct`). Rows marked _immutable_ are guarded by database triggers as well
as by the services.

```mermaid
erDiagram
    users ||--o{ vault_members : "belongs to"
    vaults ||--o{ vault_members : has
    vaults ||--o{ vault_keys : "server keys (per version)"
    vaults ||--o{ vault_member_keys : "sealed keys (per member, version)"
    users ||--o| user_key_bundles : "sealed key pair"
    users ||--o{ device_approvals : "pending approvals"
    vaults ||--o{ memories : contains
    memories ||--o{ memory_assets : has
    memory_assets ||--o{ asset_derivatives : "thumb / preview / playback"
    memories ||--o{ memory_reflections : "append-only"
    memories ||--o{ memory_search_tokens : "blind index"
    memories ||--o{ deletion_requests : "delayed purge"
    users ||--o{ refresh_tokens : sessions
    users ||--o{ devices : "push tokens"
    users ||--o{ notifications : queue
    vaults ||--o{ vault_exports : "ZIP exports"
    users ||--o| account_deletions : "30-day grace"
    users ||--o{ vault_member_keys : "wrapped by (sender)"

    vaults {
        uuid id
        string type "PERSONAL | SHARED"
        string encryption "SERVER | SEALED"
        int key_version "current"
        string state "ACTIVE | DORMANT"
    }
    memories {
        uuid id
        bytea title_ct "ciphertext (also thought, feeling, location)"
        bytea coordinates_ct "lat,lng ciphertext, optional (V21)"
        string state "DRAFT SEALING LOCKED ARCHIVED"
        int key_version
        string content_hash "proof of preservation"
    }
    memory_assets {
        uuid id
        string object_key
        string checksum_sha256
        string upload_status
        string encryption_metadata "storage-sse | e2e:v1"
    }
    user_key_bundles {
        bytea public_key "immutable"
        bytea wrapped_by_passphrase
        bytea wrapped_by_recovery
    }
    vault_member_keys {
        int key_version
        bytea wrapped_key "immutable; format 2 = authenticated"
        uuid wrapped_by "sender"
        bytea wrapped_by_public_key "sender key at wrap time (V19)"
    }
    rate_limit_hits {
        bigint id
        text key "ip:..., fail:login:email|ip:..."
        timestamptz hit_at "swept after 2 h"
    }
```

Also: `audit_events` (append-only), `outbox_events`, `user_mfa`, `mfa_recovery_codes`,
`password_reset_tokens`, `notification_preferences`, `search_index_skips` (server backfill only; for a
sealed memory a skip row means "the device found no words", V18). `rate_limit_hits` (V20) stands alone:
its keys name an IP or an account, not a foreign key.

## 4. Memory lifecycle

```mermaid
stateDiagram-v2
    [*] --> DRAFT: create
    DRAFT --> DRAFT: edit, add / remove media
    DRAFT --> [*]: discard draft
    DRAFT --> SEALING: lock (every asset usable,<br/>content hash recorded)
    SEALING --> DRAFT: undo within cooling-off (24 h)
    SEALING --> LOCKED: cooling-off ends (seal job)
    LOCKED --> ARCHIVED: archive
    ARCHIVED --> LOCKED: unarchive
    LOCKED --> [*]: deletion request + 30 days (purge)
    ARCHIVED --> [*]: deletion request + 30 days (purge)
```

From SEALING onward the content columns (including the map position, `V21`) and the memory's assets
can't change: `MemoryService` refuses, and the `V1`/`V13`/`V21` triggers refuse again at the database.
The content hash includes the map position only when one is set, so proofs made before `V21` still
verify. Reflections are append-only.
Locking publishes `memory.locked`, which applies S3 object-lock retention (governance mode).

## 5. Media upload

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant A as Lock API
    participant S as Object storage
    participant W as Media worker (outbox)
    participant K as clamd
    alt server-side vault
        C->>C: SHA-256 of the bytes
        C->>A: POST /memories/{id}/assets/upload {type, mime, size, checksum}
    else sealed vault (e2e:v1, decisions §25)
        C->>C: pick asset id, encrypt original (+ photo thumbnail),<br/>file key bound to memory + asset id, hash the ciphertext
        C->>A: POST /memories/{id}/assets/upload {id, type, mime, size, checksum}
        Note over A: same id + same memory, size, checksum, type<br/>while INITIATED → fresh URL (retry),<br/>any other reuse → 409 ASSET_EXISTS
    end
    A->>A: draft owner? quota? size limit (plaintext size for sealed)
    A-->>C: presigned PUT (checksum signed in)
    C->>S: PUT bytes
    C->>A: POST /assets/{id}/complete (idempotent)
    A->>S: HEAD: size + checksum match?
    alt server-side vault
        A->>A: PENDING_SCAN, publish asset.verified
        W->>K: scan
        alt clean
            W->>A: VERIFIED, publish asset.scanned_clean
            W->>S: thumbnails / playback rendition (ffmpeg)
        else infected
            W->>S: move to quarantine/
            W->>A: REJECTED
        end
    else sealed vault
        A->>A: VERIFIED at once (no scan, no worker)
        C->>A: POST /assets/{id}/sealed-thumbnail
        C->>S: PUT encrypted thumbnail
    end
```

Upload states: `INITIATED → PENDING_SCAN → VERIFIED → ATTACHED` (at lock), or
`VERIFICATION_FAILED`, `REJECTED`, `EXPIRED` (never completed within the window).

### Offline and background upload (client, roadmap 3.5, 3.9, 3.10)

On native the client never uploads straight from the picker: every draft and file goes through a
persistent queue first, so it survives going offline or the app being closed. Sealed vaults put only
ciphertext in the queue.

```mermaid
flowchart LR
    pick[Draft saved /<br/>file picked] --> kind{vault}
    kind -- server --> copy["copy file to<br/>pending-uploads/"]
    kind -- sealed --> enc["encrypt now (key in hand):<br/>draft body, or file + thumbnail<br/>→ pending-uploads/*.lkm"]
    copy --> q[(queue in AsyncStorage)]
    enc --> q
    q --> drain{"online?<br/>(on launch, on reconnect)"}
    drain -- no --> q
    drain -- yes --> send["send in order:<br/>createDraft / createSealedDraft /<br/>upload / uploadSealed"]
    send -- ok --> done[delete local copy]
    send -- "409 STALE_KEY_VERSION<br/>(sealed draft)" --> reenc["re-encrypt under the newest<br/>key held, or wait for the vault to open"]
    reenc --> send
    send -- "4xx or 8 tries" --> failed["shown as failed:<br/>Try again / Remove"]
```

## 6. Events and background work

Domain events are written to `outbox_events` in the same transaction as the change, then dispatched
by `OutboxDispatcher` every 5 seconds (`FOR UPDATE SKIP LOCKED`, retries, dead-letter count). No event is lost if the
process dies, and none is sent for a rolled-back change.

```mermaid
flowchart LR
    subgraph tx[Same DB transaction]
        change[State change] --> ob[(outbox_events)]
    end
    ob --> disp[OutboxDispatcher]
    disp --> storage["StorageLifecycleHandler<br/>retention on lock, delete bytes on<br/>discard / purge / expiry"]
    disp --> proc["AssetProcessor<br/>scan → thumbnails, renditions"]
    disp --> notif["NotificationEventHandler<br/>lock, capsule, invite, rejected,<br/>export ready, deletion reminders"]
    disp --> exp["ExportService<br/>build ZIP"]
    disp --> mail["PasswordResetMailer"]
```

Scheduled jobs (each claims its batch with `SKIP LOCKED`, so several instances share the work):

| Job | Every | Does |
|---|---|---|
| `LifecycleJobs` | 1 min / 15 min / 30 min / 6 h | complete seals, execute due deletions, expire stale uploads, delete expired refresh tokens |
| `NotificationJobs` | 30 s; daily 09:00 UTC | send queued push notifications; enqueue "on this day" |
| `AssetProcessor` sweeps | 5 min / 30 min | re-scan stuck uploads; backfill missing derivatives |
| `ColdTierJob` | 6 h | tag old originals for the cold tier (if enabled) |
| `SearchBackfillJob` | 15 min | index memories written before search existed (sealed vaults excluded by query; devices re-index those via `GET /vaults/{id}/search/unindexed`) |
| `VaultKeyRewrapper` | 10 min | re-wrap vault keys after a KEK rotation |
| `AccountDeletionJob` | 15 min | execute account deletions after the grace period |
| `ExportService.housekeeping` | 1 h | delete expired export archives |
| `DeviceApprovalService.deleteExpired` | 15 min | drop device approvals older than 10 minutes |
| `ScannerProbe`, `LifecycleRuleCheck` | 1 min / 6 h | feed the `lock_scan_up` and `lock_storage_exports_lifecycle` gauges |
| `PostgresRateLimiter.sweep` | 10 min | delete rate-limit hits older than 2 h (Postgres store only; idempotent, no lock needed) |

## 7. Encryption

Two vault types share one schema. A vault's type is chosen at creation and never changes.

```mermaid
flowchart TB
    subgraph server["SERVER vault (default, decisions §1, §15)"]
        kek["KEK<br/>local master key or AWS KMS"]
        vk1["vault data key v1, v2, …<br/>(vault_keys, wrapped by the KEK)"]
        ct1["content: AES-256-GCM<br/>AAD = recordId:field"]
        blind1["search key = HMAC(vault key)<br/>→ blind tokens (server)"]
        kek --> vk1 --> ct1
        vk1 --> blind1
    end
    subgraph sealed["SEALED vault (end-to-end, roadmap 3.6, decisions §24)"]
        pass["passphrase<br/>(Argon2id ≥ 19 MiB, t=2)"]
        phrase["24-word recovery phrase<br/>(HKDF)"]
        usk["user X25519 secret key<br/>(made on the device)"]
        vk2["vault data key v1, v2, …<br/>(made on a member's device)"]
        ct2["content: AES-256-GCM, AAD = id:field<br/>media: 1 MiB chunked AES-GCM"]
        blind2["search key = HMAC(vault key)<br/>→ blind tokens (device)"]
        pass -- wraps --> usk
        phrase -- wraps --> usk
        usk -- "unwraps one copy per member (vault_member_keys),<br/>format 2: authenticated by the sender's key" --> vk2
        pins["pinned member keys<br/>(on each device)"] -. "wrap only to / accept only from<br/>keys that match their pin" .-> vk2
        vk2 --> ct2
        vk2 --> blind2
    end
```

- **Rotation:** removing a member (or a member leaving) bumps `vaults.key_version`. New content uses
  the new version; old content keeps its version, because re-encrypting locked ciphertext would break
  immutability and the integrity proof (§15). Sealed vaults: the next member device to open the vault
  generates the new version and must wrap it for every remaining member at once.
- **What the server can do:** for SERVER vaults it decrypts to serve content, build exports, index
  search and make thumbnails. For SEALED vaults it stores and returns ciphertext only and refuses
  plaintext (`PLAINTEXT_IN_SEALED_VAULT`); thumbnails, search tokens and exports are made on the device.
- **Sealed key sharing (format 2, security review F1/F2):** each wrapped copy is authenticated by the
  wrapping member's X25519 key, and every device pins each member's key on first sight. See the sequence
  below and [`sealed-vault-protocol.md`](sealed-vault-protocol.md) for byte layouts.
- **Password reset** restores the account and every SERVER vault; a SEALED vault needs the passphrase,
  the recovery phrase or an approved device.

### Opening a sealed vault: authenticated keys and pins (roadmap 3.8)

```mermaid
sequenceDiagram
    autonumber
    participant D as Member device (unlocked)
    participant P as Pins (app storage)
    participant A as Lock API
    D->>A: GET /vaults/{id}/keys
    A-->>D: my wrapped copies (+ wrappedBy, wrappedByPublicKey),<br/>members' public keys, who lacks the current version
    D->>D: server lists my own key correctly? (else stop)
    D->>P: compare members' keys with pins<br/>(unseen → pin, different → "changed")
    loop each of my copies
        alt format 2
            D->>P: sender's pinned key (or pin the stored one)
            D->>D: open with X25519(me, ephemeral) ‖ X25519(me, sender)
        else format 1 (legacy)
            D->>P: version ≤ this vault's legacy ceiling? (else ignore)
            D->>D: open anonymous box
        end
    end
    alt nobody holds the current version (member left)
        D->>D: refuse while any member's key is "changed"
        D->>A: POST /keys/{v}: new key wrapped (format 2) for every member
    else someone joined
        D->>A: POST /keys/{v}: current key wrapped for them (never to a changed key)
    end
    Note over A: refuses format 1 uploads (LEGACY_KEY_FORMAT),<br/>stores the wrapper's public key on each row
```

A changed key is resolved in Members: the user compares fingerprints in person, then accepts the new
key. Members can also be marked "Verified in person".

### Approving a new device for sealed vaults (roadmap 3.6e)

```mermaid
sequenceDiagram
    autonumber
    participant N as New device (keys locked)
    participant A as Lock API
    participant O as Unlocked device
    N->>N: one-time X25519 key pair
    N->>A: POST /account/device-approvals {requestKey}
    A-->>N: id, fingerprint
    N->>N: show QR code (request id + fingerprint)
    O->>N: scan QR (camera)
    O->>A: GET /account/device-approvals
    O->>O: server's request key has the QR's fingerprint? (else stop)
    O->>A: POST /{id}/approve {account secret key sealed to the one-time key}
    loop every 3 s
        N->>A: POST /{id}/collect
    end
    A-->>N: sealed key, once (then the row is deleted)
    N->>N: unwrap, check against the account public key, store in Keychain
```

The fingerprint comparison is what stops the server, or someone with a stolen access token, from
inserting its own request key.

## 8. Request security

- **Authentication:** 15-minute HS256 access JWTs plus rotating 30-day refresh tokens with reuse
  detection (a reused token revokes the whole family). Optional TOTP second step with recovery codes;
  sensitive actions (deletions, exports, key bundle changes) need the password and, if MFA is on, a code.
- **Authorization:** every vault call checks active membership in the database; non-members get 404 so
  vault ids can't be probed. Drafts are visible only to their creator.
- **Abuse:** per-IP limits on auth endpoints. Wrong passwords lock out the guessing IP for that account
  (5 in 15 min), with an account-wide backstop across all IPs (50). Counters live in Postgres
  (`rate_limit_hits`, `PostgresRateLimiter`) so every instance shares them; development can use memory.
- **Production guard:** the `prod` profile refuses to start with dev secrets, the dev malware scanner,
  the log-only email sender, or per-instance rate limits (`ProductionSecretsGuard`).

```mermaid
flowchart LR
    req["POST /auth/login<br/>from IP x"] --> f{"AuthRateLimitFilter:<br/>&lt; 20 / min from IP x<br/>on this endpoint?"}
    f -- no --> r429a[429 RATE_LIMITED]
    f -- yes --> g{"LoginAttemptGuard:<br/>&lt; 5 failures for (email, x)<br/>and &lt; 50 for email, in 15 min?"}
    g -- no --> r429b[429 TOO_MANY_ATTEMPTS]
    g -- yes --> pw{password right?}
    pw -- no --> fail["count (email, x) and (email)<br/>in its own transaction → 401"]
    pw -- yes --> ok["clear (email, x) → tokens<br/>(or MFA step)"]
    store[("rate_limit_hits<br/>shared by every instance")] -.- f & g
```

Counting per (account, IP) means a stranger guessing from their own machine can't lock the owner out
(decisions §26). A password reset clears every IP's count for the account.

## 9. Deployment and operations

```mermaid
flowchart LR
    lb[Load balancer] --> api1[Lock API]
    lb --> api2[Lock API]
    api1 & api2 --> pg[(PostgreSQL)]
    api1 & api2 --> s3[(S3 bucket<br/>object lock,<br/>s3-lifecycle.json)]
    api1 & api2 --> clamd[clamd<br/>deploy/clamd.conf]
    prom[Prometheus<br/>lock-alerts.yml] -. ":LOCK_MANAGEMENT_PORT/actuator/prometheus" .-> api1 & api2
```

- **Instances:** stateless; any number can run. Jobs and the outbox share work through
  `SKIP LOCKED`. Rate limits and lockouts are shared through Postgres (`LOCK_RATE_LIMIT_STORE=postgres`).
- **Configuration:** `deploy/production.env.example` lists every production variable;
  `deploy/README.md` has the checklist.
- **Health:** `/actuator/health/readiness` checks the database and storage; `/liveness` is separate.
- **Metrics and alerts:** Prometheus scrapes the private management port. Alert rules in
  `deploy/prometheus/lock-alerts.yml` cover the scanner (down, stuck, growing backlog), the exports
  lifecycle rule, the outbox (dead events, lag), seal lag, integrity failures and stale KEK wrapping.
  `AlertRulesTest` fails the build if a rule uses a metric the API doesn't export.
- **Logs:** one JSON object per line with `LOCK_LOG_FORMAT=ecs`; every line carries the request id.
  No tokens, passwords or decrypted content are logged.

## 10. Client

```
client/src/
  app/          screens (Expo Router); _layout.tsx guards signed-in routes with Stack.Protected
  api/          client.ts (fetch, single-flight token refresh), lock.ts (every endpoint), types.ts
  theme/        design tokens (light/dark palette, spacing, radius); minimalist design system (roadmap 4.1)
  components/   UI kit (ui.tsx), AssetGallery (incl. sealed tiles), MediaPlayer (expo-video),
                MemoryMap (expo-maps, clustering; *.web.tsx renders nothing), MemoriesMapView (map + place list),
                PinPicker (crosshair pin picker; none on web), SealedGate, QrCode, MemoryForm
  lib/
    session.tsx, tokenStore.ts   auth state; tokens in SecureStore (sessionStorage on web)
    offlineQueue.ts               offline drafts + background uploads (sealed vaults: ciphertext only)
    push.ts                       push registration and taps
    cryptoRandom.ts               secure randomness for @noble/* on native; imported first
    sealed.ts                     E2E crypto: key pair, passphrase/phrase wraps, vault-key boxes, fields
    sealedMedia.ts                chunked media encryption format
    sealedSearch.ts               on-device blind index (port of SearchTokenizer)
    sealedVault.ts, sealedDecrypt.ts, useSealed.ts   keys on this device, open vault, encrypt/decrypt, re-index older memories
    keyPins.ts                    pinned member keys (trust on first use, changed-key refusal, verified flag)
    geo.ts                        map positions: GPS fix, on-device geocoding, map camera, clustering (5.1, 5.2)
    mapData.ts                    map loaders: one vault, or every vault the device can read (5.2)
    sealedFiles.ts, sealedExport.ts                  streaming file encryption, chunk-streamed ZIP export, cache sweep
    deviceApproval.ts             QR device approval
```

The client runs on Expo SDK 58 (React Native 0.88). Sealed vault keys are wrapped with the
authenticated format v2, and member keys are pinned on each device (`docs/security-review-sealed-vault.md`;
byte-level specification: `docs/sealed-vault-protocol.md`).

Server state goes through TanStack Query. Sealed content is decrypted inside the API wrappers, so
screens see plaintext once the device holds the key and an "Encrypted" placeholder otherwise.

### Memory map (roadmap 5.1)

```mermaid
sequenceDiagram
    autonumber
    participant U as Member
    participant C as Client
    participant M as Maps provider (Apple / Google)
    participant A as Lock API
    U->>C: "Use my location", "Find ‘Where’ on the map" or "Pick on the map"
    alt GPS
        C->>C: when-in-use permission, current fix
    else place lookup
        C->>M: geocode the typed place name (on request only)
    else pick on the map (5.2)
        C->>C: pan under the crosshair (map taps move it on Android / iOS 18), "Pin here"
    end
    C->>A: save draft with coordinates<br/>(server vault: plaintext over TLS, encrypted at rest,<br/>sealed: "lat,lng" encrypted on the device)
    Note over A: coordinates_ct frozen at lock (V21 trigger),<br/>in the content hash when present
    U->>C: vault → Map, or Home → Map of all memories (5.2)
    C->>A: timeline pages of each vault (coordinates decrypted by the API or on the device),<br/>all-vaults map: sealed vaults only if this device's keys open them
    opt "Place by ‘Where’" switch (unpinned memories)
        C->>M: geocode their place names (this session only, never stored)
    end
    C->>M: map tiles (expo-maps: Apple Maps iOS 17+, Google Maps Android)
    C->>U: markers (nearby pins grouped with a count, tap zooms in) + list grouped by place (web: list only)
```

Android builds need `GOOGLE_MAPS_ANDROID_API_KEY` (read by `client/app.config.ts`); iOS needs no key.
