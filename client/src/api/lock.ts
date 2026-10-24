import { request, requestWithHeaders } from './client';
import type {
  AccountDeletion,
  Asset,
  AssetDownload,
  AssetType,
  AssetVariant,
  DeletionRequest,
  DevicePlatform,
  IntegrityReport,
  MfaChallenge,
  MfaSetup,
  MfaStatus,
  Me,
  Member,
  Memory,
  MemoryInput,
  MemoryState,
  NotificationPreferences,
  Reflection,
  Relive,
  StorageUsage,
  TimelinePage,
  TokenResponse,
  UploadTicket,
  Vault,
  VaultExport,
  VaultType,
} from './types';

/** Typed wrappers over every Lock API endpoint the client uses. */
export const api = {
  // Auth
  register: (email: string, password: string, displayName: string) =>
    request<TokenResponse>('POST', '/auth/register', { email, password, displayName }),
  /** Returns a session, or an MFA challenge when two-step verification is on. */
  login: (email: string, password: string) =>
    request<TokenResponse | MfaChallenge>('POST', '/auth/login', { email, password }),
  loginMfa: (mfaToken: string, code: string) =>
    request<TokenResponse>('POST', '/auth/login/mfa', { mfaToken, code }),
  me: () => request<Me>('GET', '/auth/me'),
  logout: (refreshToken: string) => request<void>('POST', '/auth/logout', { refreshToken }),
  /** Always succeeds (202), whether or not the email has an account. */
  requestPasswordReset: (email: string) => request<void>('POST', '/auth/password-reset', { email }),
  confirmPasswordReset: (token: string, newPassword: string) =>
    request<void>('POST', '/auth/password-reset/confirm', { token, newPassword }),
  /** Signs out every other device and returns a new session for this one. */
  changePassword: (currentPassword: string, newPassword: string) =>
    request<TokenResponse>('POST', '/auth/change-password', { currentPassword, newPassword }),

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
  /** One timeline page. `nextCursor` is set when more memories exist; pass it back as `cursor`. */
  timeline: async (
    vaultId: string,
    params: { state?: MemoryState; limit?: number; cursor?: string } = {},
  ): Promise<TimelinePage> => {
    const q = new URLSearchParams();
    if (params.state) q.set('state', params.state);
    if (params.limit) q.set('limit', String(params.limit));
    if (params.cursor) q.set('cursor', params.cursor);
    const qs = q.toString();
    const { data, headers } = await requestWithHeaders<Memory[]>('GET', `/vaults/${vaultId}/memories${qs ? `?${qs}` : ''}`);
    return { memories: data, nextCursor: headers.get('Next-Cursor') ?? undefined };
  },
  /** Every word must match; words of 3+ letters also match as prefixes. Newest first. */
  search: (vaultId: string, q: string) =>
    request<Memory[]>('GET', `/vaults/${vaultId}/search?q=${encodeURIComponent(q)}`),
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
  requestDeletion: (memoryId: string, password: string, reason?: string, mfaCode?: string) =>
    request<DeletionRequest>('POST', `/memories/${memoryId}/deletion-request`, { password, reason }, mfa(mfaCode)),
  cancelDeletion: (memoryId: string) => request<DeletionRequest>('DELETE', `/memories/${memoryId}/deletion-request`),

  // Assets
  initiateUpload: (memoryId: string, type: AssetType, mimeType: string, sizeBytes: number, checksumSha256: string) =>
    request<UploadTicket>('POST', `/memories/${memoryId}/assets/upload`, { type, mimeType, sizeBytes, checksumSha256 }),
  completeUpload: (assetId: string) => request<Asset>('POST', `/assets/${assetId}/complete`),
  /** With a variant, returns that derivative when it exists and the original otherwise. */
  downloadUrl: (assetId: string, variant?: AssetVariant) =>
    request<AssetDownload>('GET', `/assets/${assetId}/download${variant ? `?variant=${variant.toLowerCase()}` : ''}`),
  removeAsset: (assetId: string) => request<void>('DELETE', `/assets/${assetId}`),

  // ZIP exports (manifest + original media)
  requestExport: (vaultId: string, mfaCode?: string) =>
    request<VaultExport>('POST', `/vaults/${vaultId}/exports`, undefined, mfa(mfaCode)),
  exports: (vaultId: string) => request<VaultExport[]>('GET', `/vaults/${vaultId}/exports`),
  exportDownloadUrl: (exportId: string) => request<{ url: string }>('GET', `/exports/${exportId}/download`),

  // Push notifications
  registerDevice: (pushToken: string, platform: DevicePlatform) =>
    request<unknown>('POST', '/devices', { pushToken, platform }),
  unregisterDevice: (pushToken: string) => request<void>('DELETE', '/devices', { pushToken }),
  notificationPreferences: () => request<NotificationPreferences>('GET', '/notification-preferences'),
  updateNotificationPreferences: (changes: Partial<NotificationPreferences>) =>
    request<NotificationPreferences>('PATCH', '/notification-preferences', changes),

  // Account deletion (30-day grace period, cancellable)
  requestAccountDeletion: (password: string, mfaCode?: string) =>
    request<AccountDeletion>('POST', '/account/deletion-request', { password }, mfa(mfaCode)),
  accountDeletion: () => request<AccountDeletion>('GET', '/account/deletion-request'),
  cancelAccountDeletion: () => request<AccountDeletion>('DELETE', '/account/deletion-request'),

  /** Bytes of original media used against the plan's quota. */
  storage: () => request<StorageUsage>('GET', '/account/storage'),

  // Two-step verification (TOTP)
  mfaStatus: () => request<MfaStatus>('GET', '/account/mfa'),
  mfaSetup: () => request<MfaSetup>('POST', '/account/mfa/setup'),
  mfaConfirm: (code: string) => request<{ recoveryCodes: string[] }>('POST', '/account/mfa/confirm', { code }),
  mfaRegenerateRecoveryCodes: (code: string) =>
    request<{ recoveryCodes: string[] }>('POST', '/account/mfa/recovery-codes', { code }),
  mfaDisable: (code: string) => request<void>('POST', '/account/mfa/disable', { code }),
};

/** Sensitive calls carry the current authenticator code in a header once two-step verification is on. */
function mfa(code?: string): Record<string, string> {
  return code ? { 'X-MFA-Code': code.trim() } : {};
}
