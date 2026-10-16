import { request } from './client';
import type {
  Asset,
  AssetType,
  DeletionRequest,
  IntegrityReport,
  Me,
  Member,
  Memory,
  MemoryInput,
  MemoryState,
  Reflection,
  Relive,
  TokenResponse,
  UploadTicket,
  Vault,
  VaultType,
} from './types';

/** Typed wrappers over every Lock API endpoint the client uses. */
export const api = {
  // Auth
  register: (email: string, password: string, displayName: string) =>
    request<TokenResponse>('POST', '/auth/register', { email, password, displayName }),
  login: (email: string, password: string) =>
    request<TokenResponse>('POST', '/auth/login', { email, password }),
  me: () => request<Me>('GET', '/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('POST', '/auth/change-password', { currentPassword, newPassword }),

  // Vaults
  vaults: () => request<Vault[]>('GET', '/vaults'),
  vault: (vaultId: string) => request<Vault>('GET', `/vaults/${vaultId}`),
  createVault: (type: VaultType, name: string) => request<Vault>('POST', '/vaults', { type, name }),
  members: (vaultId: string) => request<Member[]>('GET', `/vaults/${vaultId}/members`),
  invite: (vaultId: string, email: string) => request<Member>('POST', `/vaults/${vaultId}/members`, { email }),
  removeMember: (vaultId: string, userId: string) =>
    request<void>('DELETE', `/vaults/${vaultId}/members/${userId}`),
  leaveVault: (vaultId: string) => request<void>('POST', `/vaults/${vaultId}/leave`),
  invitations: () => request<Member[]>('GET', '/invitations'),
  respondToInvitation: (vaultId: string, accept: boolean) =>
    request<Member>('POST', `/invitations/${vaultId}/${accept ? 'accept' : 'decline'}`),

  // Memories
  timeline: (vaultId: string, params: { state?: MemoryState; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.state) q.set('state', params.state);
    if (params.limit) q.set('limit', String(params.limit));
    const qs = q.toString();
    return request<Memory[]>('GET', `/vaults/${vaultId}/memories${qs ? `?${qs}` : ''}`);
  },
  memory: (memoryId: string) => request<Memory>('GET', `/memories/${memoryId}`),
  createMemory: (vaultId: string, input: MemoryInput, idempotencyKey: string) =>
    request<Memory>('POST', `/vaults/${vaultId}/memories`, input, { 'Idempotency-Key': idempotencyKey }),
  updateDraft: (memoryId: string, input: MemoryInput) => request<Memory>('PATCH', `/memories/${memoryId}`, input),
  discardDraft: (memoryId: string) => request<void>('DELETE', `/memories/${memoryId}/draft`),
  lock: (memoryId: string) => request<Memory>('POST', `/memories/${memoryId}/lock`),
  cancelLock: (memoryId: string) => request<Memory>('POST', `/memories/${memoryId}/lock/cancel`),
  archive: (memoryId: string, archived: boolean) =>
    request<Memory>('POST', `/memories/${memoryId}/${archived ? 'archive' : 'unarchive'}`),
  relive: (memoryId: string) => request<Relive>('GET', `/memories/${memoryId}/relive`),
  onThisDay: () => request<Memory[]>('GET', '/memories/on-this-day'),
  reflections: (memoryId: string) => request<Reflection[]>('GET', `/memories/${memoryId}/reflections`),
  addReflection: (memoryId: string, body: string) =>
    request<Reflection>('POST', `/memories/${memoryId}/reflections`, { body }),
  integrity: (memoryId: string) => request<IntegrityReport>('GET', `/memories/${memoryId}/integrity`),
  requestDeletion: (memoryId: string, password: string, reason?: string) =>
    request<DeletionRequest>('POST', `/memories/${memoryId}/deletion-request`, { password, reason }),
  cancelDeletion: (memoryId: string) => request<DeletionRequest>('DELETE', `/memories/${memoryId}/deletion-request`),

  // Assets
  initiateUpload: (memoryId: string, type: AssetType, mimeType: string, sizeBytes: number, checksumSha256: string) =>
    request<UploadTicket>('POST', `/memories/${memoryId}/assets/upload`, { type, mimeType, sizeBytes, checksumSha256 }),
  completeUpload: (assetId: string) => request<Asset>('POST', `/assets/${assetId}/complete`),
  downloadUrl: (assetId: string) => request<{ url: string }>('GET', `/assets/${assetId}/download`),
  removeAsset: (assetId: string) => request<void>('DELETE', `/assets/${assetId}`),
};
