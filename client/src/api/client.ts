import Constants from 'expo-constants';

import type { Problem } from './types';

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

let accessToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

/** Called when the API rejects the token, so the session can sign out. */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE';

export async function request<T>(
  method: Method,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  const res = await fetch(API_URL + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (res.status === 401 && accessToken) {
    onUnauthorized?.();
  }
  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try {
      problem = await res.json();
    } catch {
      // Non-JSON error body.
    }
    throw new ApiError(res.status, problem.code, problem.detail ?? `Request failed (${res.status})`);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}
