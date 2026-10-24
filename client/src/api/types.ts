// Mirrors the backend's response records (backend/src/main/java/app/lock/**/…Controller.java).

export type VaultType = 'PERSONAL' | 'SHARED';
export type VaultState = 'ACTIVE' | 'DORMANT';
export type MemberRole = 'OWNER' | 'MEMBER';
export type MemberStatus = 'INVITED' | 'ACTIVE' | 'DECLINED' | 'REMOVED' | 'LEFT';
export type MemoryState = 'DRAFT' | 'SEALING' | 'LOCKED' | 'ARCHIVED';
export type AssetType = 'PHOTO' | 'VIDEO' | 'AUDIO';
export type UploadStatus =
  | 'INITIATED'
  | 'PENDING_SCAN'
  | 'VERIFIED'
  | 'VERIFICATION_FAILED'
  | 'REJECTED'
  | 'ATTACHED'
  | 'EXPIRED';
/**
 * Renditions: THUMB (256 px) and PREVIEW (1024 px) JPEGs of a photo or a video still, and PLAYBACK,
 * an H.264/AAC MP4 of a video or audio file that plays on every device.
 */
export type AssetVariant = 'THUMB' | 'PREVIEW' | 'PLAYBACK';
export type ExportStatus = 'QUEUED' | 'READY' | 'FAILED' | 'EXPIRED';
export type DevicePlatform = 'IOS' | 'ANDROID' | 'WEB';
/** DELETION_REMINDER is a safety feature and cannot be turned off. */
export type NotificationCategory =
  | 'MEMORY_LOCKED'
  | 'CAPSULE_REVEALED'
  | 'ON_THIS_DAY'
  | 'VAULT_INVITATION'
  | 'ASSET_REJECTED'
  | 'EXPORT_READY'
  | 'DELETION_REMINDER';
export type NotificationPreferences = Record<NotificationCategory, boolean>;

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  refreshToken: string;
  refreshExpiresAt: string;
}

/** Login answer when two-step verification is on: no session yet, send a code with this token. */
export interface MfaChallenge {
  mfaRequired: true;
  mfaToken: string;
  mfaTokenExpiresAt: string;
}

export interface StorageUsage {
  plan: string;
  usedBytes: number;
  limitBytes: number;
  remainingBytes: number;
}

export interface MfaStatus {
  enabled: boolean;
  enabledAt: string | null;
  recoveryCodesLeft: number;
}

export interface MfaSetup {
  secret: string;
  otpauthUri: string;
}

export interface Me {
  id: string;
  email: string;
  displayName: string;
  createdAt: string;
}

export interface Vault {
  id: string;
  type: VaultType;
  name: string;
  ownerId: string;
  state: VaultState;
  createdAt: string;
}

export interface Member {
  vaultId: string;
  userId: string;
  role: MemberRole;
  status: MemberStatus;
  invitedAt: string | null;
  joinedAt: string | null;
  /** Set on the member list. A deleted account shows as "Former member". */
  displayName: string | null;
}

export interface Asset {
  id: string;
  type: AssetType;
  mimeType: string;
  sizeBytes: number;
  checksumSha256: string;
  uploadStatus: UploadStatus;
  createdAt: string;
  /** Derivatives that exist; empty means only the original is available. */
  variants: AssetVariant[];
}

export interface AssetDownload {
  url: string;
  /** The derivative served, or null for the original. */
  variant: AssetVariant | null;
}

export interface VaultExport {
  id: string;
  vaultId: string;
  status: ExportStatus;
  sizeBytes: number | null;
  memoryCount: number | null;
  assetCount: number | null;
  error: string | null;
  requestedAt: string;
  completedAt: string | null;
  expiresAt: string | null;
}

export interface AccountDeletion {
  id: string;
  status: 'PENDING' | 'CANCELLED' | 'COMPLETED';
  requestedAt: string;
  executeAfter: string;
}

export interface Memory {
  id: string;
  vaultId: string;
  createdBy: string;
  state: MemoryState;
  title: string | null;
  thought: string | null;
  feeling: string | null;
  location: string | null;
  occurredAt: string;
  revealAt: string | null;
  sealedCapsule: boolean;
  sealingUntil: string | null;
  lockedAt: string | null;
  contentHash: string | null;
  createdAt: string;
  assets: Asset[];
}

export interface MemoryInput {
  title: string;
  thought?: string | null;
  feeling?: string | null;
  location?: string | null;
  occurredAt?: string | null;
  revealAt?: string | null;
}

export interface Reflection {
  id: string;
  authorId: string;
  body: string;
  createdAt: string;
}

export interface Relive {
  memory: Memory;
  daysSinceLocked: number;
  reflections: Reflection[];
  samePeriod: Memory[];
}

export interface IntegrityReport {
  memoryId: string;
  state: MemoryState;
  intact: boolean;
  sealedHash: string;
  currentHash: string;
  lockedAt: string | null;
  daysPreserved: number;
  assetsChecked: number;
  assetFailures: string[];
}

export interface DeletionRequest {
  id: string;
  memoryId: string;
  status: 'PENDING' | 'CANCELLED' | 'COMPLETED';
  requestedAt: string;
  executeAfter: string;
}

export interface UploadTicket {
  assetId: string;
  uploadUrl: string;
  method: 'PUT';
  headers: Record<string, string>;
  expiresAt: string;
}

/** RFC 9457 problem body; `code` is Lock's stable error code. */
export interface Problem {
  status: number;
  detail?: string;
  code?: string;
}

export interface TimelinePage {
  memories: Memory[];
  /** Opaque; pass back as `cursor` to load the next page. Undefined on the last page. */
  nextCursor?: string;
}
