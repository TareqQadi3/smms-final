import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import axios from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  STORAGE_KEYS,
  api,
  authApi,
  buildLoginUrl,
  navigation,
  readAccessToken,
  readRefreshToken,
  resetSessionStateForTests,
  safeNextPath,
  storeTokens,
  subscribeSession,
} from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

type Reply = { status: number; data?: unknown };
type Call = { method: string; url: string; auth: string | null; refreshHeader: string | null };

/** Installs one fake network for both the `api` instance and the bare axios used by the refresh call. */
function installNetwork(handler: (call: Call, config: InternalAxiosRequestConfig) => Reply | Promise<Reply>) {
  const calls: Call[] = [];
  const adapter: AxiosAdapter = async (config) => {
    const call: Call = {
      method: (config.method ?? 'get').toLowerCase(),
      url: config.url ?? '',
      auth: (config.headers.get('Authorization') as string | undefined) ?? null,
      refreshHeader: (config.headers.get('X-Refresh-Token') as string | undefined) ?? null,
    };
    calls.push(call);
    const reply = await handler(call, config);
    const response = { data: reply.data ?? {}, status: reply.status, statusText: '', headers: {}, config };
    if (reply.status >= 400) {
      throw new AxiosError(`status ${reply.status}`, 'ERR_BAD_REQUEST', config, null, response);
    }
    return response;
  };
  api.defaults.adapter = adapter;
  axios.defaults.adapter = adapter;
  return calls;
}

function networkDown(): never {
  throw new AxiosError('Network Error', 'ERR_NETWORK');
}

beforeEach(() => {
  localStorage.clear();
  resetSessionStateForTests();
  vi.spyOn(navigation, 'assign').mockImplementation(() => {});
  vi.spyOn(navigation, 'currentPath').mockReturnValue('/tech/42?tab=notes');
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('single-flight refresh', () => {
  it('runs ONE refresh for concurrent 401s, then retries each request once with the new token', async () => {
    storeTokens('old-access', 'refresh-1');
    let refreshCount = 0;
    const calls = installNetwork(async (call) => {
      if (call.url === '/auth/refresh' || call.url.endsWith('/auth/refresh')) {
        refreshCount += 1;
        await new Promise((r) => setTimeout(r, 20)); // widen the window in which the other 401s arrive
        return { status: 200, data: { token: 'new-access', refreshToken: 'refresh-2' } };
      }
      if (call.auth === 'Bearer new-access') return { status: 200, data: { ok: call.url } };
      return { status: 401 };
    });

    const results = await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')]);

    expect(refreshCount).toBe(1);
    expect(results.map((r) => r.data.ok)).toEqual(['/a', '/b', '/c']);
    // each original path was sent twice (401, retry) and never more
    for (const path of ['/a', '/b', '/c']) {
      expect(calls.filter((c) => c.url === path)).toHaveLength(2);
    }
    // the rotated pair is persisted and the OLD refresh token was the only one ever presented
    expect(readAccessToken()).toBe('new-access');
    expect(readRefreshToken()).toBe('refresh-2');
    expect(calls.filter((c) => c.url.endsWith('/auth/refresh')).map((c) => c.refreshHeader)).toEqual(['refresh-1']);
    expect(navigation.assign).not.toHaveBeenCalled();
  });

  it('a request that failed with an outdated token is retried with the current one without refreshing', async () => {
    storeTokens('stale', 'refresh-1');
    const calls = installNetwork(async (call) => {
      if (call.url.endsWith('/auth/refresh')) return { status: 200, data: { token: 'x', refreshToken: 'y' } };
      if (call.auth === 'Bearer fresh') return { status: 200, data: { ok: true } };
      // while this request is on the wire another tab rotated the tokens
      storeTokens('fresh', 'refresh-2');
      return { status: 401 };
    });
    const res = await api.get('/slow');
    expect(res.data.ok).toBe(true);
    expect(calls.some((c) => c.url.endsWith('/auth/refresh'))).toBe(false);
  });

  it('a rejected refresh (401, reuse detected) logs out fully and redirects to /login keeping the path', async () => {
    storeTokens('old', 'refresh-1');
    localStorage.setItem(STORAGE_KEYS.user, '{"id":"u1"}');
    const ended = vi.fn();
    const unsubscribe = subscribeSession(ended);
    installNetwork(async (call) => {
      if (call.url.endsWith('/auth/refresh')) return { status: 401, data: { message: 'Invalid token' } };
      return { status: 401 };
    });

    const outcomes = await Promise.allSettled([api.get('/a'), api.get('/b')]);
    unsubscribe();

    expect(outcomes.every((o) => o.status === 'rejected')).toBe(true);
    expect(readAccessToken()).toBeNull();
    expect(readRefreshToken()).toBeNull();
    expect(localStorage.getItem(STORAGE_KEYS.user)).toBeNull();
    expect(ended).toHaveBeenCalledWith({ type: 'ended', reason: 'expired' });
    expect(navigation.assign).toHaveBeenCalledTimes(1); // two failures, one redirect
    expect(navigation.assign).toHaveBeenCalledWith('/login?expired=1&next=%2Ftech%2F42%3Ftab%3Dnotes');
    expect(useAuthStore.getState().sessionExpired).toBe(true);
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('a missing refresh token ends the session', async () => {
    localStorage.setItem(STORAGE_KEYS.access, 'only-access');
    installNetwork(async () => ({ status: 401 }));
    await expect(api.get('/a')).rejects.toBeTruthy();
    expect(navigation.assign).toHaveBeenCalledTimes(1);
  });

  it('keeps the session when the refresh call fails on the network (offline), so queued work can retry later', async () => {
    storeTokens('old', 'refresh-1');
    installNetwork(async (call) => {
      if (call.url.endsWith('/auth/refresh')) networkDown();
      return { status: 401 };
    });
    await expect(api.get('/a')).rejects.toMatchObject({ code: 'ERR_NETWORK' });
    expect(readRefreshToken()).toBe('refresh-1');
    expect(navigation.assign).not.toHaveBeenCalled();
  });

  it('keeps the session on a 503 from the refresh endpoint', async () => {
    storeTokens('old', 'refresh-1');
    installNetwork(async (call) => (call.url.endsWith('/auth/refresh') ? { status: 503 } : { status: 401 }));
    await expect(api.get('/a')).rejects.toMatchObject({ response: { status: 503 } });
    expect(readAccessToken()).toBe('old');
    expect(navigation.assign).not.toHaveBeenCalled();
  });

  it('ends the session when the retried request is rejected again', async () => {
    storeTokens('old', 'refresh-1');
    installNetwork(async (call) => {
      if (call.url.endsWith('/auth/refresh')) return { status: 200, data: { token: 'n', refreshToken: 'r2' } };
      return { status: 401 };
    });
    await expect(api.get('/a')).rejects.toBeTruthy();
    expect(navigation.assign).toHaveBeenCalledTimes(1);
  });

  it('a 401 from login is a normal answer: no refresh, no redirect', async () => {
    storeTokens('old', 'refresh-1');
    const calls = installNetwork(async () => ({ status: 401 }));
    await expect(authApi.login('u', 'bad')).rejects.toBeTruthy();
    expect(calls).toHaveLength(1);
    expect(navigation.assign).not.toHaveBeenCalled();
    expect(readAccessToken()).toBe('old');
  });

  it('does not bounce public /status pages to /login', async () => {
    vi.spyOn(navigation, 'currentPath').mockReturnValue('/status/abc');
    storeTokens('old', 'refresh-1');
    installNetwork(async () => ({ status: 401 }));
    await expect(api.get('/a')).rejects.toBeTruthy();
    expect(navigation.assign).not.toHaveBeenCalled();
  });
});

describe('login and logout', () => {
  it('login stores both tokens', async () => {
    installNetwork(async () => ({ status: 200, data: { token: 'acc', refreshToken: 'ref', role: 'technician' } }));
    await authApi.login('u', 'p');
    expect(readAccessToken()).toBe('acc');
    expect(readRefreshToken()).toBe('ref');
  });

  it('logout calls the API with the access and refresh token, then clears everything', async () => {
    storeTokens('acc', 'ref');
    localStorage.setItem(STORAGE_KEYS.user, '{"id":"u1"}');
    useAuthStore.setState({ user: { id: 'u1', username: 'u', fullName: 'U', role: 'technician', active: true }, token: 'acc' });
    const calls = installNetwork(async () => ({ status: 204 }));

    useAuthStore.getState().logout();
    await vi.waitFor(() => expect(calls).toHaveLength(1));

    expect(calls[0]).toMatchObject({ method: 'post', url: '/auth/logout', auth: 'Bearer acc', refreshHeader: 'ref' });
    expect(readAccessToken()).toBeNull();
    expect(readRefreshToken()).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('logout still clears the local session when the API is unreachable', async () => {
    storeTokens('acc', 'ref');
    installNetwork(async () => networkDown());
    useAuthStore.getState().logout();
    expect(readAccessToken()).toBeNull();
    expect(readRefreshToken()).toBeNull();
  });
});

describe('redirect helpers', () => {
  it('only accepts same-site absolute paths as next', () => {
    expect(safeNextPath('/tech/1')).toBe('/tech/1');
    expect(safeNextPath('//evil.example')).toBeNull();
    expect(safeNextPath('https://evil.example')).toBeNull();
    expect(safeNextPath('/\\evil')).toBeNull();
    expect(safeNextPath('/login?next=/x')).toBeNull();
    expect(safeNextPath(null)).toBeNull();
  });

  it('builds the login url', () => {
    expect(buildLoginUrl('/tech/1', true)).toBe('/login?expired=1&next=%2Ftech%2F1');
    expect(buildLoginUrl('//x', false)).toBe('/login');
  });
});
