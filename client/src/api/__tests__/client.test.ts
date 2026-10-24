/// <reference types="jest" />
import { ApiError, getRefreshToken, request, setTokens, setTokensChangedHandler, setUnauthorizedHandler } from '../client';

/**
 * The silent token refresh in client.ts (roadmap 1.1, F9). Refresh tokens are single-use: two
 * refreshes with the same token look like theft and sign the user out everywhere. These tests pin
 * the behaviour that prevents that.
 */

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function tokenResponse(n: number) {
  return { accessToken: `access-${n}`, tokenType: 'Bearer', expiresAt: '', refreshToken: `refresh-${n}`, refreshExpiresAt: '' };
}

let handler: Handler;
const calls: { url: string; auth?: string; body?: string }[] = [];

beforeEach(() => {
  calls.length = 0;
  setTokens({ accessToken: 'access-0', refreshToken: 'refresh-0' });
  setTokensChangedHandler(null);
  setUnauthorizedHandler(null);
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    const headers = (init.headers ?? {}) as Record<string, string>;
    calls.push({ url, auth: headers.Authorization, body: init.body as string | undefined });
    return handler(url, init);
  }) as typeof fetch;
});

/** Protected endpoint: 401 (no code) unless called with the given access token. */
function protectedFor(token: string, body: unknown = { ok: true }): Handler {
  return (url, init) => {
    if (url.endsWith('/auth/refresh')) return json(200, tokenResponse(1));
    const auth = (init.headers as Record<string, string>).Authorization;
    return auth === `Bearer ${token}` ? json(200, body) : json(401, { status: 401 });
  };
}

test('an expired access token is refreshed once and the request retried', async () => {
  handler = protectedFor('access-1', { hello: 'world' });
  const changed = jest.fn();
  setTokensChangedHandler(changed);

  await expect(request('GET', '/vaults')).resolves.toEqual({ hello: 'world' });

  expect(calls.map((c) => new URL(c.url).pathname)).toEqual(['/vaults', '/auth/refresh', '/vaults']);
  expect(JSON.parse(calls[1].body!)).toEqual({ refreshToken: 'refresh-0' });
  expect(calls[2].auth).toBe('Bearer access-1');
  expect(changed).toHaveBeenCalledWith({ accessToken: 'access-1', refreshToken: 'refresh-1' });
  expect(getRefreshToken()).toBe('refresh-1');
});

test('concurrent 401s share a single refresh', async () => {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  handler = async (url, init) => {
    if (url.endsWith('/auth/refresh')) {
      await gate;
      return json(200, tokenResponse(1));
    }
    return protectedFor('access-1')(url, init);
  };

  const all = Promise.all([request('GET', '/a'), request('GET', '/b'), request('GET', '/c')]);
  await new Promise((r) => setTimeout(r, 0));
  release();
  await all;

  expect(calls.filter((c) => c.url.endsWith('/auth/refresh'))).toHaveLength(1);
});

test('a rejected refresh token signs out', async () => {
  handler = (url) => (url.endsWith('/auth/refresh') ? json(401, { status: 401, code: 'INVALID_REFRESH_TOKEN' }) : json(401, { status: 401 }));
  const unauthorized = jest.fn();
  setUnauthorizedHandler(unauthorized);

  await expect(request('GET', '/vaults')).rejects.toBeInstanceOf(ApiError);
  expect(unauthorized).toHaveBeenCalledTimes(1);
});

test('a network failure during refresh keeps the session', async () => {
  handler = (url) => {
    if (url.endsWith('/auth/refresh')) throw new TypeError('Network request failed');
    return json(401, { status: 401 });
  };
  const unauthorized = jest.fn();
  setUnauthorizedHandler(unauthorized);

  await expect(request('GET', '/vaults')).rejects.toBeInstanceOf(ApiError);
  expect(unauthorized).not.toHaveBeenCalled();
  expect(getRefreshToken()).toBe('refresh-0');
});

test('a 401 with a domain code (wrong password) is not treated as an expired session', async () => {
  handler = () => json(401, { status: 401, code: 'INVALID_CREDENTIALS', detail: 'Current password is incorrect' });
  const unauthorized = jest.fn();
  setUnauthorizedHandler(unauthorized);

  await expect(request('POST', '/auth/change-password', {})).rejects.toMatchObject({
    status: 401,
    code: 'INVALID_CREDENTIALS',
    message: 'Current password is incorrect',
  });
  expect(calls.filter((c) => c.url.endsWith('/auth/refresh'))).toHaveLength(0);
  expect(unauthorized).not.toHaveBeenCalled();
});
