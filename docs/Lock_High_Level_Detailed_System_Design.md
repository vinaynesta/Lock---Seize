# Lock --- High-Level & Detailed System Design

**Document Status:** Draft v1.0\
**Product:** Lock\
**Purpose:** Architecture and engineering design for a privacy-first
memory preservation platform\
**Primary concept:** Users intentionally lock memories so they remain
preserved and cannot be casually deleted. Memories can belong to an
individual vault or a shared vault.

------------------------------------------------------------------------

## 1. Executive Summary

Lock is a memory-preservation application built around a simple product
promise:

> **Some memories deserve to be permanent.**

Unlike a conventional diary or photo-storage application, Lock treats a
locked memory as an intentionally preserved digital artifact.

A memory can contain:

-   Photos
-   Videos
-   Text/thoughts
-   Feelings/reflections
-   Dates and timestamps
-   Optional locations
-   Other contextual metadata

Users can create:

1.  **Personal Vaults** --- accessible only by the owner.
2.  **Shared Vaults** --- accessible by multiple authorized members.

The defining architectural property is:

> **The locked memory content is immutable.**

The surrounding system may evolve --- passwords, devices, UI, storage
tiers, membership metadata, and infrastructure can change --- but the
original locked memory must remain preserved.

The system therefore needs stronger guarantees than a typical CRUD
application:

-   Durable storage
-   Strong authorization
-   Encryption and key management
-   Immutable memory state
-   Reliable media upload
-   Disaster recovery
-   Auditability
-   Controlled shared-vault access
-   Scalable media processing
-   Safe account/key recovery

------------------------------------------------------------------------

# 2. Product Vision

## 2.1 Core Problem

Human memory is lossy.

As new experiences accumulate, people forget:

-   What they were thinking
-   How they felt
-   What they experienced
-   Small details surrounding important events
-   Memories that once felt extremely important

Existing applications primarily optimize for:

-   Storing photos
-   Writing journals
-   Messaging
-   Cloud backup
-   Social sharing

Lock focuses on a different behavior:

> **Intentionally selecting a memory and preserving it for future
> retrieval.**

------------------------------------------------------------------------

## 2.2 Product Philosophy

### Capture

A user records a meaningful moment.

### Lock

The user intentionally marks the memory as preserved.

### Preserve

The system protects the memory from casual deletion or mutation.

### Relive

The user can return to the memory later and experience the original
context again.

------------------------------------------------------------------------

# 3. Goals

## 3.1 Primary Goals

1.  Preserve locked memories reliably.
2.  Keep locked memory content immutable.
3.  Protect memory content through strong authorization.
4.  Support personal and shared vaults.
5.  Support large media such as photos and videos.
6.  Allow users to change credentials without re-encrypting all content.
7.  Provide reliable disaster recovery.
8.  Scale storage and processing independently.
9.  Maintain an audit history for important memory lifecycle events.
10. Make the architecture suitable for gradual growth from MVP to
    large-scale production.

------------------------------------------------------------------------

## 3.2 Secondary Goals

-   Search and browse memories efficiently.
-   Generate thumbnails and media derivatives.
-   Provide timeline-based memory discovery.
-   Support future AI features without compromising privacy.
-   Support multiple devices.
-   Provide secure account recovery.
-   Enable future multi-person vaults.

------------------------------------------------------------------------

# 4. Non-Goals for Initial Version

The initial system should not attempt to solve everything.

Potentially defer:

-   Full social networking
-   Public profiles
-   Public memory sharing
-   Complex recommendation algorithms
-   Advanced AI-generated memories
-   Multi-region active-active architecture
-   Arbitrary fine-grained per-file permissions
-   Blockchain-based permanence
-   Unlimited collaboration semantics

The MVP should prove the core proposition:

> **Create → Lock → Preserve → Relive**

------------------------------------------------------------------------

# 5. Core Domain Concepts

## 5.1 User

Represents an authenticated person.

``` text
User
 ├── identity
 ├── authentication credentials
 ├── devices
 ├── vault memberships
 └── recovery configuration
```

------------------------------------------------------------------------

## 5.2 Vault

A security and organizational boundary containing memories.

Types:

``` text
PERSONAL
SHARED
```

Examples:

``` text
My Vault
Our First Trip
Family Memories
College Memories
```

------------------------------------------------------------------------

## 5.3 Vault Member

Represents the relationship between a user and a vault.

``` text
VaultMember
 ├── user_id
 ├── vault_id
 ├── role
 ├── status
 ├── joined_at
 └── permissions
```

Possible roles:

``` text
OWNER
MEMBER
```

The permission model should remain extensible.

------------------------------------------------------------------------

## 5.4 Memory

The central domain object.

A memory represents the logical experience being preserved.

``` text
Memory
 ├── id
 ├── vault_id
 ├── title
 ├── description/thought
 ├── feeling
 ├── occurred_at
 ├── created_at
 ├── state
 └── metadata
```

Possible states:

``` text
DRAFT
LOCKED
ARCHIVED
```

For MVP, the most important transition is:

``` text
DRAFT → LOCKED
```

Once locked, the original memory content becomes immutable.

------------------------------------------------------------------------

## 5.5 Memory Asset

A memory may contain one or more media objects.

``` text
Memory
 ├── Asset 1 → photo
 ├── Asset 2 → photo
 ├── Asset 3 → video
 └── Asset 4 → audio
```

The database stores metadata.

Object storage stores the actual bytes.

------------------------------------------------------------------------

## 5.6 Memory Event

Important lifecycle events should be represented separately.

Examples:

``` text
MEMORY_CREATED
ASSET_UPLOADED
MEMORY_LOCKED
MEMORY_VIEWED
MEMORY_RELIVED
MEMBER_ADDED
MEMBER_REMOVED
```

This creates an auditable history without mutating the original memory
content.

------------------------------------------------------------------------

# 6. Core Architectural Principle

## Immutable Core + Mutable Context

The most important design principle for Lock is:

> **The memory is immutable. Everything around the memory can evolve.**

For example:

``` text
                  MEMORY
                    |
          +---------+---------+
          |                   |
       CONTENT             CONTEXT
          |                   |
     Photos/Videos        Feeling
     Original Thought    Location
     Original Metadata   Participants
          |                   |
          +---------+---------+
                    |
                  LOCK
                    |
               IMMUTABLE
```

The following may change:

-   Password
-   Device
-   UI
-   Storage location
-   Storage tier
-   Membership metadata
-   Notifications
-   Search indexes

The original locked memory content must remain preserved.

------------------------------------------------------------------------

# 7. High-Level Architecture

``` text
                         +--------------------+
                         |      Mobile/Web    |
                         |       Client       |
                         +---------+----------+
                                   |
                                   | HTTPS
                                   v
                         +--------------------+
                         |    API Gateway /   |
                         |   Load Balancer    |
                         +---------+----------+
                                   |
                         +---------v----------+
                         |     Lock API       |
                         |   Application      |
                         +---------+----------+
                                   |
              +--------------------+--------------------+
              |                    |                    |
              v                    v                    v
       +-------------+      +-------------+      +-------------+
       | Identity &  |      | Vault &     |      | Memory &    |
       | Auth        |      | Membership  |      | Authorization|
       +-------------+      +-------------+      +-------------+
                                   |
                                   v
                         +--------------------+
                         |   Metadata DB      |
                         |  Relational DB     |
                         +--------------------+
                                   |
             +---------------------+---------------------+
             |                                           |
             v                                           v
      +--------------+                           +---------------+
      | Object       |                           | Queue / Event |
      | Storage      |                           | Bus           |
      +--------------+                           +-------+-------+
                                                        |
                                           +------------+------------+
                                           |            |            |
                                           v            v            v
                                     Media Worker  Notification  Index Worker
```

------------------------------------------------------------------------

# 8. Component Responsibilities

## 8.1 Client Application

Responsibilities:

-   Authentication UI
-   Vault browsing
-   Memory creation
-   Media selection
-   Upload orchestration
-   Memory locking
-   Memory viewing
-   Timeline
-   Relive experience
-   Local secure session management

The client should never contain authoritative authorization rules.

The server remains the authority.

------------------------------------------------------------------------

## 8.2 API Gateway / Load Balancer

Responsibilities:

-   TLS termination
-   Request routing
-   Rate limiting
-   Authentication token forwarding
-   DDoS/WAF integration
-   Request size controls

------------------------------------------------------------------------

## 8.3 Identity Service

Responsibilities:

-   Registration
-   Login
-   Session management
-   Credential changes
-   Device management
-   Account recovery
-   Authentication factors

Authentication and authorization should remain conceptually separate.

------------------------------------------------------------------------

## 8.4 Vault Service

Responsibilities:

-   Create vault
-   Update vault metadata
-   Invite members
-   Accept invitations
-   Manage membership
-   Validate vault ownership
-   Enforce vault-level policies

------------------------------------------------------------------------

## 8.5 Memory Service

Responsibilities:

-   Create memory
-   Add content
-   Lock memory
-   Read memory
-   Generate memory events
-   Enforce immutability
-   Validate state transitions

The Memory Service owns the most important domain rule:

``` text
LOCKED memory content cannot be mutated through normal user operations.
```

------------------------------------------------------------------------

## 8.6 Media Service

Responsibilities:

-   Generate upload authorization
-   Validate asset metadata
-   Track upload state
-   Generate download URLs
-   Manage thumbnails
-   Coordinate media processing

Large files should bypass the application server where possible.

------------------------------------------------------------------------

## 8.7 Background Workers

Potential worker types:

``` text
Media Processor
Thumbnail Generator
Video Transcoder
Metadata Extractor
Security Scanner
Notification Worker
Search Indexer
Cleanup/Reconciliation Worker
```

------------------------------------------------------------------------

# 9. Storage Architecture

## 9.1 Metadata Database

A relational database is a strong default because Lock has:

-   Users
-   Vaults
-   Memberships
-   Memory state
-   Transactions
-   Authorization relationships
-   Upload states
-   Audit records

Possible choice:

``` text
PostgreSQL / MySQL
```

PostgreSQL is a strong candidate for the initial architecture.

------------------------------------------------------------------------

## 9.2 Object Storage

Actual media should be stored separately.

Examples conceptually:

``` text
Object Storage
 ├── encrypted original photo
 ├── encrypted original video
 ├── encrypted audio
 └── encrypted derivatives
```

The relational DB stores references such as:

``` text
asset_id
memory_id
object_key
content_type
size
checksum
encryption_metadata
upload_status
```

------------------------------------------------------------------------

# 10. Why Not Store Photos in the Database?

Storing large binary objects directly inside the primary database
creates several problems:

-   Database growth
-   Backup size
-   Expensive replication
-   Slower database operations
-   Harder scaling
-   Higher operational cost

Separating metadata and media gives:

``` text
Database → structured information
Object storage → large binary data
```

------------------------------------------------------------------------

# 11. Media Upload Architecture

A large upload should not normally travel through the application
server.

Preferred flow:

``` text
Client
   |
   | 1. Request upload
   v
Lock API
   |
   | 2. Generate signed upload permission
   v
Client
   |
   | 3. Direct upload
   v
Object Storage
   |
   | 4. Upload completion
   v
Lock API
   |
   | 5. Verify
   v
Memory Asset = VERIFIED
```

This prevents the API layer from becoming a bandwidth bottleneck.

------------------------------------------------------------------------

# 12. Upload State Machine

Uploads should not be assumed to be atomic.

``` text
INITIATED
    |
    v
UPLOADING
    |
    v
UPLOADED
    |
    v
VERIFIED
    |
    v
ATTACHED
```

Failure examples:

``` text
INITIATED → EXPIRED
UPLOADING → ABANDONED
UPLOADED → VERIFICATION_FAILED
```

A reconciliation worker can periodically identify incomplete uploads.

------------------------------------------------------------------------

# 13. Memory State Machine

The memory lifecycle should be explicit.

``` text
                  +---------+
                  |  DRAFT  |
                  +----+----+
                       |
                       | Lock
                       v
                  +---------+
                  | LOCKED  |
                  +----+----+
                       |
                       | Optional lifecycle metadata
                       v
                  +---------+
                  | ARCHIVED|
                  +---------+
```

Critical invariant:

``` text
DRAFT:
    content may change

LOCKED:
    original content cannot change

ARCHIVED:
    content remains preserved
```

The API should reject invalid transitions.

Example:

``` text
PATCH /memory/123
```

If:

``` text
memory.state == LOCKED
```

then modifying original content should fail.

------------------------------------------------------------------------

# 14. "Cannot Delete" Semantics

The UI must not be the only protection.

Bad implementation:

``` text
Hide Delete button
```

Good implementation:

``` text
Authorization Layer
        +
Domain State Machine
        +
Database Constraints
        +
Object Lifecycle Controls
        +
Audit Trail
        +
Backup Strategy
```

The user should not have a normal API operation that can delete a locked
memory.

------------------------------------------------------------------------

# 15. Encryption Architecture

Encryption should be designed around a hierarchy of keys.

A conceptual hierarchy:

``` text
User Credential
       |
       v
Key Derivation
       |
       v
Key Encryption Key
       |
       v
Vault Key
       |
       v
Memory / Asset Encryption Key
       |
       v
Encrypted Content
```

The exact cryptographic implementation should use established, audited
cryptographic primitives rather than custom cryptography.

------------------------------------------------------------------------

# 16. Why Hierarchical Keys?

Suppose a vault contains:

``` text
10,000 photos
500 videos
20 GB
```

If a password change required re-encrypting every object:

``` text
Password change
      |
      +--> decrypt 20 GB
      |
      +--> re-encrypt 20 GB
```

that would be inefficient.

Instead:

``` text
Old password
    ↓
Old key protection
    ↓
Vault Key

New password
    ↓
New key protection
    ↓
Same Vault Key
```

The content remains encrypted under the same underlying data key.

------------------------------------------------------------------------

# 17. Shared Vault Encryption

For a shared vault:

``` text
                 Vault Key
                /         \
               /           \
         User A Key       User B Key
```

Each authorized member can obtain access to the vault key through their
own authenticated key path.

This supports:

-   Independent credentials
-   Password changes
-   Multiple devices
-   Shared access

without re-encrypting every memory when a user's password changes.

------------------------------------------------------------------------

# 18. Authentication vs Authorization

## Authentication

Answers:

> Who is this user?

## Authorization

Answers:

> Can this user access this vault and memory?

Example:

``` text
User A
  |
  +--> Vault X = OWNER
  |
  +--> Vault Y = MEMBER
  |
  +--> Vault Z = NO ACCESS
```

Authorization must be checked server-side for every protected operation.

------------------------------------------------------------------------

# 19. Shared Vault Membership

A shared vault may use:

``` text
Vault
 ├── Owner
 ├── Member A
 ├── Member B
 └── Member C
```

The MVP should keep the permission model simple.

Suggested initial roles:

``` text
OWNER
MEMBER
```

Avoid complex per-memory permissions initially.

------------------------------------------------------------------------

# 20. Password Changes

Password changes should not require memory re-encryption.

Conceptually:

``` text
Old Password
     ↓
Old Credential Key
     ↓
Vault Key

New Password
     ↓
New Credential Key
     ↓
Same Vault Key
```

This is an important reason to separate authentication credentials from
content encryption keys.

------------------------------------------------------------------------

# 21. Lost Password / Recovery Trade-off

This is one of the hardest product decisions.

Strong end-to-end encryption can create a fundamental trade-off:

``` text
More server access to keys
        ↓
Easier recovery
        ↓
Lower privacy/security boundary
```

versus:

``` text
Less server access
        ↓
Stronger privacy
        ↓
Harder recovery
```

Possible approaches:

### Approach A --- Server-assisted recovery

Easier UX.

Trade-off:

-   Server has greater cryptographic responsibility.
-   Strongest end-to-end privacy becomes harder.

### Approach B --- User recovery key

Higher security.

Trade-off:

-   User can permanently lose access if the recovery key is lost.

### Approach C --- Social/shared recovery

Potentially useful later.

Trade-off:

-   More complexity.
-   Shared-vault security becomes more complicated.

The MVP should explicitly choose a recovery philosophy before
implementing encryption.

------------------------------------------------------------------------

# 22. Shared Vault Member Removal

This is a difficult scenario.

Suppose:

``` text
Vault
 ├── User A
 └── User B
```

Then B is removed.

If B previously obtained the encryption key, simply removing B from the
database does not magically erase information B may already possess.

Therefore:

> **Authorization revocation cannot guarantee retroactive deletion of
> previously decrypted data.**

For future access, the system can rotate the vault key:

``` text
Old Vault Key
       ↓
Rotate
       ↓
New Vault Key
```

New content uses the new key.

Existing content may require controlled re-wrapping or re-encryption
depending on the exact cryptographic design.

This is an important trade-off to document early.

------------------------------------------------------------------------

# 23. Database Schema --- High Level

## Users

``` text
users
-----
id
email / username
status
created_at
updated_at
```

## Vaults

``` text
vaults
------
id
type
name
owner_id
state
created_at
updated_at
```

## Vault Members

``` text
vault_members
-------------
vault_id
user_id
role
status
joined_at
removed_at
```

## Memories

``` text
memories
--------
id
vault_id
created_by
title
thought
feeling
occurred_at
state
locked_at
created_at
```

## Memory Assets

``` text
memory_assets
-------------
id
memory_id
object_key
asset_type
mime_type
size
checksum
upload_status
encryption_metadata
created_at
```

## Memory Events

``` text
memory_events
-------------
id
memory_id
event_type
actor_id
event_metadata
created_at
```

## Devices

``` text
devices
-------
id
user_id
device_identifier
public_key_metadata
last_seen_at
created_at
```

------------------------------------------------------------------------

# 24. API Design --- High Level

## Authentication

``` text
POST /auth/register
POST /auth/login
POST /auth/refresh
POST /auth/logout
POST /auth/change-password
POST /auth/recovery
```

------------------------------------------------------------------------

## Vaults

``` text
POST   /vaults
GET    /vaults
GET    /vaults/{vaultId}
POST   /vaults/{vaultId}/members
DELETE /vaults/{vaultId}/members/{userId}
```

------------------------------------------------------------------------

## Memories

``` text
POST /vaults/{vaultId}/memories
GET  /vaults/{vaultId}/memories
GET  /memories/{memoryId}
POST /memories/{memoryId}/lock
```

Avoid exposing a normal:

``` text
DELETE /memories/{memoryId}
```

for locked memories.

------------------------------------------------------------------------

## Assets

``` text
POST /memories/{memoryId}/assets/upload
POST /assets/{assetId}/complete
GET  /assets/{assetId}/download
```

------------------------------------------------------------------------

# 25. Example: Create Memory Flow

``` text
Client
  |
  | POST /vaults/{id}/memories
  v
Memory Service
  |
  | Validate vault membership
  |
  | Create DRAFT memory
  v
Database
  |
  v
Client receives memory_id
```

------------------------------------------------------------------------

# 26. Example: Upload Photo Flow

``` text
Client
   |
   | Request upload
   v
Lock API
   |
   | Validate memory ownership
   |
   | Generate upload authorization
   v
Object Storage
   |
   | Upload
   v
Object Storage
   |
   | Completion
   v
Lock API
   |
   | Verify checksum/metadata
   v
Asset = VERIFIED
```

------------------------------------------------------------------------

# 27. Example: Lock Memory Flow

``` text
Client
   |
   | POST /memories/{id}/lock
   v
Memory Service
   |
   | Verify authorization
   | Verify required assets
   | Start transaction
   |
   | DRAFT → LOCKED
   |
   | Create MEMORY_LOCKED event
   |
   | Commit
   v
Database
   |
   v
Queue
   |
   +--> Notification
   +--> Search indexing
   +--> Timeline processing
```

The critical state transition should be transactional.

------------------------------------------------------------------------

# 28. Event-Driven Processing

Not every operation needs synchronous execution.

For example:

``` text
Memory Locked
      |
      v
Event Queue
      |
      +----> Notification Worker
      |
      +----> Search Index Worker
      |
      +----> Timeline Worker
      |
      +----> Analytics Worker
```

This prevents the user-facing request from depending on every downstream
service.

------------------------------------------------------------------------

# 29. Idempotency

Distributed systems can receive duplicate requests.

Example:

``` text
Client
  |
  | Lock memory
  |-----> API
  |
  | network timeout
  |
  | retry
  |-----> API
```

The system should not create two lock events or perform inconsistent
state transitions.

Use idempotency keys where appropriate:

``` text
Idempotency-Key: abc-123
```

Workers should also be designed to tolerate duplicate event delivery.

------------------------------------------------------------------------

# 30. Consistency Model

Not everything requires strong consistency.

### Strong consistency

Use for:

-   Memory state transitions
-   Vault membership
-   Authorization
-   Lock operation
-   Key metadata changes

### Eventual consistency

Acceptable for:

-   Search indexes
-   Notification delivery
-   Thumbnail availability
-   Analytics
-   Recommendation systems
-   Non-critical timeline indexes

This separation improves scalability.

------------------------------------------------------------------------

# 31. Caching

Potential cache candidates:

``` text
Vault metadata
User profile
Authorization metadata
Timeline pages
Frequently accessed memory metadata
```

But avoid making cache the source of truth for critical authorization.

Correct model:

``` text
Database = source of truth
Cache = optimization
```

If cache and DB disagree, security must favor the authoritative store.

------------------------------------------------------------------------

# 32. Search Architecture

Initially:

``` text
Database queries
```

may be enough.

At scale:

``` text
Database
    |
    v
Search Index
```

Search could support:

-   Title
-   Thought
-   Date
-   Vault
-   Tags
-   Location
-   Participants

Search indexes should be treated as derived data.

If an index disappears:

``` text
Rebuild from source of truth
```

------------------------------------------------------------------------

# 33. Timeline Architecture

A timeline is a natural Lock experience.

Example:

``` text
2026
 ├── Jan
 ├── Feb
 ├── Mar
 └── Apr
```

For each memory:

``` text
Date
 ↓
Memory
 ↓
Preview
 ↓
Relive
```

Timeline data can eventually be denormalized for performance, but the
canonical memory remains in the primary data store.

------------------------------------------------------------------------

# 34. Relive Experience

Relive is not just:

``` text
GET /memory
```

It is a product experience.

Potential flow:

``` text
"You locked this memory 3 years ago."

        ↓

Original photo

        ↓

Original thought

        ↓

Feeling

        ↓

Location

        ↓

Other memories from the same period
```

This feature can become one of Lock's strongest differentiators.

------------------------------------------------------------------------

# 35. Reliability Requirements

The system should plan around failures.

Examples:

-   API server crash
-   Database node failure
-   Object storage failure
-   Queue outage
-   Worker crash
-   Network timeout
-   Duplicate request
-   Partial upload
-   Corrupted object
-   Lost device
-   Credential compromise
-   Deployment failure

The architecture should assume failures will happen.

------------------------------------------------------------------------

# 36. Disaster Recovery

At minimum:

``` text
Primary Database
      |
      +--> Replication
      |
      +--> Backup
```

Object storage:

``` text
Primary objects
      |
      +--> Replication
      |
      +--> Backup/versioning
```

Recovery goals should eventually be expressed as:

### RPO --- Recovery Point Objective

How much data can we afford to lose?

### RTO --- Recovery Time Objective

How quickly can the system recover?

For a memory-preservation product, the target should become
progressively stricter as the product matures.

------------------------------------------------------------------------

# 37. Data Integrity

Media should have integrity verification.

Example:

``` text
Photo
  ↓
Checksum
  ↓
Stored metadata
```

During verification:

``` text
Uploaded bytes
      ↓
Calculate checksum
      ↓
Compare expected checksum
```

This helps detect incomplete or corrupted uploads.

------------------------------------------------------------------------

# 38. Observability

Production Lock should expose:

## Metrics

-   Request latency
-   Error rate
-   Upload success rate
-   Upload failure rate
-   Queue depth
-   Worker failure rate
-   Storage consumption
-   Memory lock rate
-   Database performance

## Logs

Structured logs with:

``` text
request_id
user_id
vault_id
memory_id
operation
result
latency
```

Avoid logging sensitive memory content.

Never log:

-   Passwords
-   Encryption keys
-   Private memory contents
-   Access tokens

------------------------------------------------------------------------

# 39. Security Threat Model

Important threats include:

### Account takeover

Mitigation:

-   Strong authentication
-   Session management
-   Device controls
-   Rate limiting
-   Optional MFA

### Unauthorized vault access

Mitigation:

-   Server-side authorization
-   Vault membership checks
-   Short-lived access tokens
-   Audit logs

### Object URL leakage

Mitigation:

-   Short-lived signed URLs
-   Authorization before generating URLs
-   No permanent public media URLs

### Database compromise

Mitigation:

-   Encryption at rest
-   Key separation
-   Least privilege
-   Secret management
-   Auditing

### Malicious upload

Mitigation:

-   File validation
-   MIME validation
-   Malware scanning
-   Size limits
-   Safe media processing

------------------------------------------------------------------------

# 40. Privacy Architecture

Privacy should be treated as a first-class requirement.

Principles:

``` text
Collect minimum necessary data
        +
Encrypt sensitive content
        +
Restrict access
        +
Avoid unnecessary logging
        +
Provide transparent controls
```

Location metadata should be optional.

Users should understand what is stored.

------------------------------------------------------------------------

# 41. Account Deletion vs Locked Memory

This is one of the biggest product-policy decisions.

The product says:

> Locked memories should not be casually deleted.

But a user may eventually request account deletion.

Therefore the product needs an explicit policy.

Possible options:

### Policy A --- Account deletion deletes everything

Simplest legally/operationally.

But conflicts somewhat with the permanence philosophy.

### Policy B --- Locked memories survive account deletion

Strongest permanence model.

But creates major privacy, legal, and ownership implications.

### Policy C --- Shared vaults behave differently from personal vaults

For example:

``` text
Personal Vault
→ account deletion removes access/content according to policy

Shared Vault
→ shared memories remain for other members
```

This should be decided as a product/legal requirement before finalizing
the deletion architecture.

------------------------------------------------------------------------

# 42. Scaling Strategy

## Stage 1 --- MVP

``` text
Mobile/Web
   |
Single API application
   |
PostgreSQL
   |
Object Storage
```

Plus a small worker process.

This is enough to validate the product.

------------------------------------------------------------------------

## Stage 2 --- Growing Product

Add:

``` text
Load Balancer
Multiple API instances
Redis/cache
Queue
Worker pool
Search index
Monitoring
```

Architecture:

``` text
                    Load Balancer
                         |
             +-----------+-----------+
             |           |           |
            API         API         API
             |           |           |
             +-----------+-----------+
                         |
                     Database
                         |
             +-----------+-----------+
             |                       |
        Object Storage             Queue
                                     |
                            +--------+--------+
                            |        |        |
                         Worker   Worker   Worker
```

------------------------------------------------------------------------

## Stage 3 --- Large Scale

Potential separation:

``` text
Identity Service
Vault Service
Memory Service
Media Service
Notification Service
Search Service
Processing Platform
```

Only split services when there is a measurable reason:

-   Independent scaling
-   Team ownership
-   Failure isolation
-   Deployment independence
-   Domain boundaries

------------------------------------------------------------------------

# 43. Key Scaling Bottlenecks

The likely bottlenecks are not necessarily CPU.

They will probably be:

1.  Media storage
2.  Media bandwidth
3.  Video processing
4.  Database indexes
5.  Timeline queries
6.  Search
7.  Background queues
8.  Encryption/key operations
9.  Backup/replication
10. Authorization checks

The architecture should scale these independently.

------------------------------------------------------------------------

# 44. Cost Model

Lock has an unusual cost characteristic:

> **Every successful locked memory potentially creates permanent storage
> liability.**

Cost drivers:

``` text
Storage
+
Backups
+
Replication
+
Bandwidth
+
Media processing
+
Database
+
Search
+
Monitoring
```

Video is especially expensive.

Therefore future product decisions may include:

-   Storage limits by plan
-   Maximum video size
-   Compression
-   Efficient thumbnails
-   Storage tiers
-   Premium storage
-   Retention/archival policies where legally/product-wise appropriate

------------------------------------------------------------------------

# 45. Major Trade-offs

## 45.1 Monolith vs Microservices

### Monolith

Pros:

-   Faster development
-   Easier debugging
-   Simple deployment
-   Strong transactions

Cons:

-   Less independent scaling
-   Larger deployment unit

### Microservices

Pros:

-   Independent scaling
-   Failure isolation
-   Team autonomy

Cons:

-   Distributed transactions
-   Network failures
-   More observability
-   More infrastructure

### Recommendation

Start modular monolith.

Extract services later based on real bottlenecks.

------------------------------------------------------------------------

## 45.2 Relational DB vs NoSQL

### Relational

Pros:

-   Strong transactions
-   Excellent relationships
-   Mature indexing
-   Good authorization modeling

### NoSQL

Pros:

-   Flexible schemas
-   Horizontal scaling
-   High write throughput for certain patterns

### Recommendation

Start with PostgreSQL or an equivalent relational database.

Object storage handles the large media.

------------------------------------------------------------------------

## 45.3 Server-Side Encryption vs Strong End-to-End Encryption

### Server-assisted encryption

Pros:

-   Easier recovery
-   Easier search/processing
-   Easier product development

Cons:

-   Server has greater access
-   Lower privacy boundary

### Strong client-side/E2E model

Pros:

-   Stronger privacy
-   Server cannot easily inspect content

Cons:

-   Key recovery is difficult
-   Search becomes harder
-   Server-side processing becomes difficult
-   Multi-device support becomes more complex

### Recommendation

Make this an explicit product/security decision early rather than
accidentally choosing one through implementation.

------------------------------------------------------------------------

## 45.4 Hard Delete vs Logical/Immutable Lifecycle

### Hard delete

Simple technically.

But directly conflicts with Lock's central concept.

### Immutable lifecycle

Better fits the product.

Recommended:

``` text
DRAFT
 ↓
LOCKED
 ↓
PRESERVED
```

No normal delete operation for locked content.

------------------------------------------------------------------------

# 46. Important Invariants

These should be treated as non-negotiable system rules.

### Invariant 1

A user cannot access a vault without authorization.

### Invariant 2

A locked memory's original content cannot be modified through normal
application APIs.

### Invariant 3

A locked memory cannot be casually deleted.

### Invariant 4

A password change must not require re-encrypting all memory content.

### Invariant 5

Object storage is not the source of authorization truth.

### Invariant 6

Search indexes are derived data, not canonical memory data.

### Invariant 7

Caches are never the sole source of security decisions.

### Invariant 8

Background processing must tolerate retries.

### Invariant 9

Every important security-sensitive lifecycle action must be auditable.

------------------------------------------------------------------------

# 47. Recommended Initial Technology Direction

This is intentionally a starting point, not a permanent commitment.

``` text
Frontend
    React Native / Flutter / Web

Backend
    Java + Spring Boot

Primary DB
    PostgreSQL

Cache
    Redis

Object Storage
    S3-compatible object storage

Queue
    Kafka / managed queue depending on scale

Search
    PostgreSQL initially
    Dedicated search later

Authentication
    OAuth/OIDC-compatible architecture or managed identity provider

Encryption
    Standard audited cryptographic primitives
    Managed KMS/HSM where appropriate

Deployment
    Containerized application

Observability
    Metrics + structured logs + distributed tracing
```

The technology can change. The domain invariants should not.

------------------------------------------------------------------------

# 48. MVP Architecture

A practical first implementation:

``` text
                    Client
                      |
                      v
                Load Balancer
                      |
                      v
             Spring Boot Application
                      |
       +--------------+--------------+
       |              |              |
       v              v              v
   PostgreSQL       Redis        Object Storage
       |
       v
   Memory/Vault
     Metadata
                      |
                      v
                    Queue
                      |
              +-------+-------+
              |               |
              v               v
        Media Worker    Notification Worker
```

Keep the number of moving pieces small.

------------------------------------------------------------------------

# 49. MVP Functional Scope

## Authentication

-   Register
-   Login
-   Logout
-   Password change
-   Basic recovery

## Vault

-   Create personal vault
-   Create shared vault
-   Invite member
-   Accept invitation
-   View members

## Memory

-   Create draft
-   Add text
-   Add feeling
-   Upload photos
-   Upload videos
-   Lock memory
-   View locked memory

## Timeline

-   Browse memories
-   Filter by date
-   Open memory
-   Relive memory

## Security

-   Authorization
-   Encrypted transport
-   Encrypted storage
-   Audit events
-   No normal delete for locked memory

------------------------------------------------------------------------

# 50. Future Architecture Opportunities

Once the core system is reliable, Lock could evolve into:

### Memory Capsules

A group of related memories.

``` text
Trip to Goa
 ├── Photos
 ├── Thoughts
 ├── Videos
 └── Timeline
```

### Time Capsules

Memory becomes available or highlighted at a future date.

### Future Self

Write something now and relive it years later.

### Shared Life Vault

Long-term shared memory between multiple people.

### Family Vault

Family-wide memories with controlled membership.

### AI Memory Assistant

Potentially:

> "Show me memories from my first year at work."

or:

> "What was I feeling around this time five years ago?"

Any AI feature must inherit the same authorization and privacy model as
the core product.

------------------------------------------------------------------------

# 51. Suggested Development Roadmap

## Phase 0 --- Architecture & Security

-   Domain model
-   State machines
-   Authorization model
-   Encryption strategy
-   Recovery strategy
-   Data retention/deletion policy

## Phase 1 --- Core MVP

-   Authentication
-   Personal vault
-   Memory creation
-   Photo upload
-   Lock operation
-   Timeline
-   Relive

## Phase 2 --- Shared Vaults

-   Invitations
-   Membership
-   Shared authorization
-   Shared encryption model
-   Member removal

## Phase 3 --- Reliability

-   Queue
-   Workers
-   Media processing
-   Backups
-   Monitoring
-   Disaster recovery

## Phase 4 --- Scale

-   Caching
-   Search
-   Storage tiers
-   Read scaling
-   Processing autoscaling

## Phase 5 --- Intelligence

-   Semantic search
-   Memory discovery
-   AI-assisted organization
-   Personalized reliving

------------------------------------------------------------------------

# 52. Critical Open Questions Before Implementation

These should be answered before finalizing the low-level design.

### Product

1.  Can a locked memory ever be deleted?
2.  What happens when a user deletes their account?
3.  Can shared members remove memories?
4.  Does removing a member revoke future access only, or trigger key
    rotation?
5.  Can a locked memory's text/feeling metadata ever be edited?
6.  Can users add new content to an existing locked memory?
7.  Can a vault have more than two people?

### Security

8.  Is Lock intended to provide true end-to-end encryption?
9.  Who should possess the ability to recover encryption keys?
10. What happens when a user loses all devices?
11. Should shared vaults require one member or multiple members to
    unlock?
12. What happens when a member becomes unauthorized?

### Storage

13. Maximum photo size?
14. Maximum video size?
15. Maximum vault size?
16. Storage limits by subscription?
17. Compression policy?
18. Storage tiering?

### Product Experience

19. What exactly happens when the user presses **Lock**?
20. Should there be a confirmation step?
21. Can a draft be deleted?
22. Should the user see a visual distinction between draft and locked
    memories?
23. How should the Relive experience work?

These answers will directly affect the architecture.

------------------------------------------------------------------------

# 53. Final Architecture Principle

Lock should not be designed as:

``` text
A diary app
+
Photo storage
+
Password
```

It should be designed as:

``` text
             LOCK
              |
      +-------+-------+
      |               |
   MEMORY          VAULT
      |               |
 Immutable        Authorized
   Content          Access
      |               |
      +-------+-------+
              |
        Secure Storage
              |
       Durable Recovery
```

The most important system property is:

> **A locked memory is a durable, authorized, immutable digital
> artifact.**

Everything else --- APIs, databases, queues, storage, encryption,
caching, search, and infrastructure --- exists to make that promise
reliable at scale.

------------------------------------------------------------------------

# 54. Recommended Next Design Step

The next document should be the **Low-Level Design (LLD)** derived from
this architecture.

It should define:

``` text
1. Exact database schema
2. Entity relationships
3. State machines
4. REST API contracts
5. Request/response models
6. Authentication flow
7. Authorization middleware
8. Encryption/key hierarchy
9. Shared-vault key management
10. Upload protocol
11. Queue/event contracts
12. Idempotency
13. Transaction boundaries
14. Error model
15. Caching strategy
16. Security controls
17. Deployment architecture
18. Failure scenarios
19. Test strategy
20. Sequence diagrams for every critical flow
```

That LLD should then become the implementation blueprint for the first
version of **Lock**.
