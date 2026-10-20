# Lock

> **Some memories deserve to be permanent.**

Lock is a privacy-first memory preservation app. You capture a moment (photos, video, voice, a
thought, a feeling), **lock** it, and Lock preserves it: protected from casual deletion or
change, and there for you to **relive** later. Memories live in personal or shared vaults.

**Create → Lock → Preserve → Relive**

- Design: [`docs/Lock_High_Level_Detailed_System_Design.md`](docs/Lock_High_Level_Detailed_System_Design.md)
- Feasibility and risks: [`docs/feasibility_analysis.txt`](docs/feasibility_analysis.txt)
- Decisions taken for the MVP: [`docs/decisions.md`](docs/decisions.md)
- Roadmap (features, effort, priority): [`docs/roadmap.md`](docs/roadmap.md)

## Repository layout

```
client/             Expo (React Native) app: iOS, Android and web
  src/app/          screens (Expo Router)
  src/api/          typed API client, direct-to-storage upload
backend/            Spring Boot 4 API (Java 21), modular monolith
  src/main/java/app/lock/
    identity/       register, login, JWT + rotating refresh tokens, password change
    vault/          personal & shared vaults, invitations, membership, authorization
    memory/         memory state machine, cooling-off, reflections, time capsules,
                    relive, integrity proof, delayed deletion, export
    media/          presigned direct uploads, verification, storage lifecycle
    crypto/         envelope encryption (KEK → vault key → content), local or AWS KMS KEK
    security/       rate limiting and login lockout
    observability/  request ids (metrics live next to the code they measure)
    audit/          append-only audit trail
    outbox/         transactional outbox + dispatcher (queue seam)
  src/main/resources/db/migration/   Flyway schema + immutability triggers
docs/               design documents
docker-compose.yml  Postgres + MinIO for local development
```

## Running locally

Requirements: JDK 21 and Node 20+. Docker is optional.

**1. API, no Docker** (embedded PostgreSQL + in-memory media; data is lost on exit, cooling-off is 2 minutes):

```bash
cd backend && ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=app.lock.LocalDevApplication
```

**1. API, with Docker** (persistent Postgres + MinIO):

```bash
docker compose up -d                 # Postgres on :5432, MinIO on :9000 (console :9001)
cd backend && ./mvnw spring-boot:run
```

Either way the API is on <http://localhost:8080> (set `PORT` to change it), with docs at `/swagger-ui.html`.

**2. Client:**

```bash
cd client
npm install
cp .env.example .env.local           # set EXPO_PUBLIC_API_URL if the API is not on localhost:8080
npx expo start                       # press w for web, i for iOS simulator, a for Android
```

On a physical phone, use your computer's LAN IP in `EXPO_PUBLIC_API_URL`. The web client's origin
must be listed in `LOCK_CORS_ORIGINS` (defaults cover `localhost:8081` and `localhost:19006`).

## Tests and checks

```bash
cd backend && ./mvnw test                       # API
cd client && npx tsc --noEmit && npx expo lint  # client
```

The tests run against a real embedded PostgreSQL (no Docker), so the database immutability
triggers are tested too, not mocked.

## Core flow (API)

| Step | Call |
|---|---|
| Sign up (a personal vault is created automatically) | `POST /auth/register` |
| Renew the 15-minute access token (rotates the refresh token) | `POST /auth/refresh` |
| Sign out (revokes the refresh token) | `POST /auth/logout` |
| Create a draft memory | `POST /vaults/{vaultId}/memories` (optional `Idempotency-Key` header) |
| Edit the draft | `PATCH /memories/{id}` |
| Attach media | `POST /memories/{id}/assets/upload` → `PUT` bytes to the returned URL → `POST /assets/{assetId}/complete` |
| Lock (starts the cooling-off window) | `POST /memories/{id}/lock` |
| Undo during cooling-off | `POST /memories/{id}/lock/cancel` |
| Timeline (paged; follow the `Next-Cursor` response header) | `GET /vaults/{vaultId}/memories?from=&to=&state=&limit=&cursor=` |
| Relive | `GET /memories/{id}/relive` |
| "On this day" | `GET /memories/on-this-day` |
| Add a reflection to a locked memory | `POST /memories/{id}/reflections` |
| Proof of preservation | `GET /memories/{id}/integrity` |
| Deliberate deletion (re-auth, 30-day delay) | `POST /memories/{id}/deletion-request` / `DELETE` to cancel |
| Export a vault | `GET /vaults/{vaultId}/export` |
| Shared vaults | `POST /vaults`, `POST /vaults/{id}/members`, `POST /invitations/{vaultId}/accept`, `POST /vaults/{id}/leave` |

There is deliberately no `DELETE /memories/{id}` for locked memories.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `LOCK_DB_URL` | `jdbc:postgresql://localhost:5432/lock` | PostgreSQL |
| `LOCK_JWT_SECRET` | dev value | HS256 signing key, base64, ≥ 32 bytes. **Set in production.** |
| `LOCK_CRYPTO_PROVIDER` | `local` | `local` (KEK from `LOCK_MASTER_KEY`) or `aws-kms` (KEK stays in AWS KMS; recommended for production) |
| `LOCK_MASTER_KEY` | dev value | Local key-encryption key, base64, 32 bytes. **Set in production** unless using `aws-kms`. |
| `LOCK_RETIRED_MASTER_KEYS` | — | Old local keys (comma-separated), used only to unwrap while vault keys are re-wrapped onto the current KEK |
| `LOCK_KMS_KEY_ID`, `LOCK_KMS_REGION` | — | KMS key ARN or `alias/...`, and its region, for `aws-kms` |
| `LOCK_COOLING_OFF` | `PT24H` | Cooling-off window before a lock becomes permanent |
| `LOCK_STORAGE_TYPE` | `s3` | `s3` (S3/MinIO/R2/B2) or `memory` (dev only; serves uploads at `/local-storage`) |
| `LOCK_CORS_ORIGINS` | `http://localhost:8081,http://localhost:19006` | Browser origins allowed to call the API |
| `LOCK_S3_ENDPOINT`, `LOCK_S3_BUCKET`, `LOCK_S3_ACCESS_KEY`, `LOCK_S3_SECRET_KEY` | MinIO defaults | Object storage |
| `LOCK_OBJECT_LOCK_YEARS` | `0` | Governance-mode retention applied to media when a memory locks |
| `LOCK_MANAGEMENT_PORT` | — | Serve `/actuator/metrics` and `/actuator/prometheus` on this private port (they are refused on the app port) |
| `LOCK_LOG_FORMAT` | plain text | `ecs` or `logstash` for one JSON object per log line |
| `LOCK_FORWARD_HEADERS` | `none` | Set to `framework` behind a load balancer so rate limits see the real client IP |
| `SPRING_PROFILES_ACTIVE` | — | `prod` refuses to start while the JWT secret, master key or S3 secret still has its dev default |

## License

MIT
