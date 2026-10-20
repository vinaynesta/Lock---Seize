import Constants from 'expo-constants';

import type { Problem, TokenResponse } from './types';

/**
 * Base URL of the Lock API. Override with EXPO_PUBLIC_API_URL (e.g. your machine's LAN IP when
 * running on a physical phone; an Android emulator reaches the host at http://10.0.2.2:8080).
 */
export const API_URL: string =
  process.env.EXPO_PUBLIC_API_URL ??
  (Constants.expoConfig?.extra?.apiUrl as string | undefined) ??
  'http://localhost:8080';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message);
  }
}

export interface Tokens {
  accessToken: string;
  refreshToken: string;
}

let tokens: Tokens | null = null;
let onTokensChanged: ((tokens: Tokens) => void) | null = null;
let onUnauthorized: (() => void) | null = null;
/** 'rejected': the server refused the refresh token (sign out). 'failed': network/429, keep the session. */
type RefreshResult = 'ok' | 'rejected' | 'failed';
let refreshing: Promise<RefreshResult> | null = null;

export function setTokens(next: Tokens | null) {
  tokens = next;
}

export function getRefreshToken(): string | null {
  return tokens?.refreshToken ?? null;
}

/** Called after a silent refresh, so the session can persist the rotated tokens. */
export function setTokensChangedHandler(handler: ((tokens: Tokens) => void) | null) {
  onTokensChanged = handler;
}

/** Called when the session can no longer be renewed, so the app can sign out. */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

/**
 * Exchanges the refresh token for a new pair. Single-flight: concurrent 401s share one refresh,
 * because each refresh token is single-use and a second exchange would look like token theft.
 */
function refreshTokens(): Promise<RefreshResult> {
  refreshing ??= (async (): Promise<RefreshResult> => {
    const current = tokens;
    if (!current) return 'rejected';
    try {
      const res = await fetch(API_URL + '/auth/refresh', {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: current.refreshToken }),
      });
      if (res.status === 401) return 'rejected';
      if (!res.ok) return 'failed';
      const body = (await res.json()) as TokenResponse;
      tokens = { accessToken: body.accessToken, refreshToken: body.refreshToken };
      onTokensChanged?.(tokens);
      return 'ok';
    } catch {
      return 'failed';
    }
  })().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE';

export async function request<T>(
  method: Method,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  return (await requestWithHeaders<T>(method, path, body, headers)).data;
}

/** Like {@link request}, but also returns the response headers (e.g. a pagination cursor). */
export async function requestWithHeaders<T>(
  method: Method,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<{ data: T; headers: Headers }> {
  const send = () =>
    fetch(API_URL + path, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

  let res = await send();
  let problem = res.ok ? null : await readProblem(res);

  // A 401 without a domain error code is the expired or rejected access token (a wrong password,
  // for instance, comes back as 401 INVALID_CREDENTIALS and must not sign the user out).
  if (res.status === 401 && tokens && !problem?.code) {
    const refreshed = await refreshTokens();
    if (refreshed === 'ok') {
      res = await send();
      problem = res.ok ? null : await readProblem(res);
    } else if (refreshed === 'rejected') {
      onUnauthorized?.();
    }
  }
  if (!res.ok) {
    const p = problem ?? { status: res.status };
    throw new ApiError(res.status, p.code, p.detail ?? `Request failed (${res.status})`);
  }
  if (res.status === 204) {
    return { data: undefined as T, headers: res.headers };
  }
  return { data: (await res.json()) as T, headers: res.headers };
}

async function readProblem(res: Response): Promise<Problem> {
  try {
    return await res.json();
  } catch {
    return { status: res.status }; // Non-JSON error body.
  }
}
