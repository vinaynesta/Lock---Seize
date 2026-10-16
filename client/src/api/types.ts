// Mirrors the backend's response records (backend/src/main/java/app/lock/**/…Controller.java).

export type VaultType = 'PERSONAL' | 'SHARED';
export type VaultState = 'ACTIVE' | 'DORMANT';
export type MemberRole = 'OWNER' | 'MEMBER';
export type MemberStatus = 'INVITED' | 'ACTIVE' | 'DECLINED' | 'REMOVED' | 'LEFT';
export type MemoryState = 'DRAFT' | 'SEALING' | 'LOCKED' | 'ARCHIVED';
export type AssetType = 'PHOTO' | 'VIDEO' | 'AUDIO';
export type UploadStatus = 'INITIATED' | 'VERIFIED' | 'VERIFICATION_FAILED' | 'ATTACHED' | 'EXPIRED';

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  refreshToken: string;
  refreshExpiresAt: string;
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
}

export interface Asset {
  id: string;
  type: AssetType;
  mimeType: string;
  sizeBytes: number;
  checksumSha256: string;
  uploadStatus: UploadStatus;
  createdAt: string;
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
