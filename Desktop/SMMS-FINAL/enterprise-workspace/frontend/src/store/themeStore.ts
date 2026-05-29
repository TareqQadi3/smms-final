import { create } from 'zustand';

export type ThemeColor = 'blue' | 'green';
export type ThemeMode  = 'light' | 'dark';

interface ThemeState {
  color: ThemeColor;
  mode:  ThemeMode;
  setColor: (c: ThemeColor) => void;
  setMode:  (m: ThemeMode)  => void;
  toggle:   ()              => void;
  init:     ()              => void;
}

function apply(color: ThemeColor, mode: ThemeMode) {
  const root = document.documentElement;
  root.setAttribute('data-theme', color);
  if (mode === 'dark') root.classList.add('dark');
  else                 root.classList.remove('dark');
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  color: 'blue',
  mode:  'light',

  init: () => {
    const color = (localStorage.getItem('smms_color') as ThemeColor) || 'blue';
    const mode  = (localStorage.getItem('smms_mode')  as ThemeMode)  || 'light';
    set({ color, mode });
    apply(color, mode);
  },

  setColor: (color) => {
    const { mode } = get();
    localStorage.setItem('smms_color', color);
    set({ color });
    apply(color, mode);
  },

  setMode: (mode) => {
    const { color } = get();
    localStorage.setItem('smms_mode', mode);
    set({ mode });
    apply(color, mode);
  },

  toggle: () => {
    const { mode, color } = get();
    const next: ThemeMode = mode === 'light' ? 'dark' : 'light';
    localStorage.setItem('smms_mode', next);
    set({ mode: next });
    apply(color, next);
  },
}));
