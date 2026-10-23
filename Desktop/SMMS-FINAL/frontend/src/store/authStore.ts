import { create } from 'zustand';
import { User } from '@/lib/types';
import { authApi } from '@/lib/api';

interface AuthState {
  user: User | null;
  token: string | null;
  lang: 'ar' | 'en';
  setAuth: (user: User, token: string) => void;
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

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  lang: 'ar',

  init: () => {
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('smms_token');
      const userStr = localStorage.getItem('smms_user');
      const lang = (localStorage.getItem('smms_lang') as 'ar' | 'en') || 'ar';
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

  setAuth: (user, token) => {
    localStorage.setItem('smms_token', token);
    localStorage.setItem('smms_user', JSON.stringify(user));
    set({ user, token });
  },

  logout: () => {
    // Axios runs request interceptors asynchronously, so pass the token explicitly instead of
    // relying on storage that is cleared right below.
    authApi.logout(localStorage.getItem('smms_token')).catch(() => {});
    localStorage.removeItem('smms_token');
    localStorage.removeItem('smms_user');
    set({ user: null, token: null });
  },

  clearPasswordChangeRequired: () => {
    set((state) => {
      if (!state.user) return state;
      const user = { ...state.user, passwordChangeRequired: false };
      localStorage.setItem('smms_user', JSON.stringify(user));
      return { user };
    });
  },

  setLang: (lang) => {
    localStorage.setItem('smms_lang', lang);
    applyDocLang(lang);
    set({ lang });
  },
}));
