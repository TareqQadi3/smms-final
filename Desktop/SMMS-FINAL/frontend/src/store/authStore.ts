import { create } from 'zustand';
import { User } from '@/lib/types';

interface AuthState {
  user: User | null;
  token: string | null;
  lang: 'ar' | 'en';
  setAuth: (user: User, token: string) => void;
  logout: () => void;
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
    localStorage.removeItem('smms_token');
    localStorage.removeItem('smms_user');
    set({ user: null, token: null });
  },

  setLang: (lang) => {
    localStorage.setItem('smms_lang', lang);
    applyDocLang(lang);
    set({ lang });
  },
}));
