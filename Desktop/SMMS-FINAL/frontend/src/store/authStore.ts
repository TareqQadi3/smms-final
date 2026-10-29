import { create } from 'zustand';
import { User } from '@/lib/types';
import {
  STORAGE_KEYS,
  authApi,
  clearSession,
  readAccessToken,
  readRefreshToken,
  storeTokens,
  subscribeSession,
} from '@/lib/api';

interface AuthState {
  user: User | null;
  token: string | null;
  lang: 'ar' | 'en';
  /** True after the session ended because the tokens could not be refreshed. */
  sessionExpired: boolean;
  setAuth: (user: User, token: string, refreshToken?: string | null) => void;
  logout: () => void;
  clearPasswordChangeRequired: () => void;
  setLang: (lang: 'ar' | 'en') => void;
  init: () => void;
}

function applyDocLang(lang: 'ar' | 'en') {
  if (typeof document === 'undefined') return;
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
}

function lsGet(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}

function lsSet(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* storage blocked */ }
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  lang: 'ar',
  sessionExpired: false,

  init: () => {
    if (typeof window !== 'undefined') {
      const token = readAccessToken();
      const userStr = lsGet(STORAGE_KEYS.user);
      const lang = (lsGet('smms_lang') as 'ar' | 'en') || 'ar';
      applyDocLang(lang);
      if (token && userStr) {
        try {
          set({ token, user: JSON.parse(userStr), lang });
        } catch { /* ignore */ }
      } else {
        set({ lang });
      }
    }
  },

  setAuth: (user, token, refreshToken) => {
    storeTokens(token, refreshToken);
    lsSet(STORAGE_KEYS.user, JSON.stringify(user));
    set({ user, token, sessionExpired: false });
  },

  logout: () => {
    // Read the tokens first, clear storage, then tell the server (it revokes the whole login session).
    // Axios runs request interceptors asynchronously, so the tokens are passed explicitly.
    const access = readAccessToken();
    const refresh = readRefreshToken();
    clearSession();
    set({ user: null, token: null, sessionExpired: false });
    if (access || refresh) authApi.logout(access, refresh).catch(() => {});
  },

  clearPasswordChangeRequired: () => {
    set((state) => {
      if (!state.user) return state;
      const user = { ...state.user, passwordChangeRequired: false };
      lsSet(STORAGE_KEYS.user, JSON.stringify(user));
      return { user };
    });
  },

  setLang: (lang) => {
    lsSet('smms_lang', lang);
    applyDocLang(lang);
    set({ lang });
  },
}));

// Keep the store in step with the API layer (token rotation, expired session) and other tabs.
if (typeof window !== 'undefined') {
  subscribeSession((e) => {
    if (e.type === 'refreshed') useAuthStore.setState({ token: e.token });
    else useAuthStore.setState({ user: null, token: null, sessionExpired: true });
  });
  window.addEventListener('storage', (ev) => {
    if (ev.key === STORAGE_KEYS.access) {
      // Another tab refreshed (new value) or logged out (null)
      useAuthStore.setState((s) => (ev.newValue
        ? { token: ev.newValue }
        : s.user ? { user: null, token: null } : s));
    }
  });
}
