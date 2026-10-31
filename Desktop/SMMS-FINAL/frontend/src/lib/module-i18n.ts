'use client';

import { useCallback, useMemo } from 'react';
import { useAuthStore } from '@/store/authStore';
import type { Lang } from '@/lib/i18n';

/** A module dictionary: every key has an Arabic string; English is optional per key. */
export interface ModuleDict<K extends string = string> {
  ar: Record<K, string>;
  en: Partial<Record<K, string>>;
}

export type TFn<K extends string> = (key: K, params?: Record<string, string | number>) => string;

/** Replaces `{name}` placeholders. Unknown placeholders are left untouched. */
export function interpolate(template: string, params?: Record<string, string | number>): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in params ? String(params[k]) : m));
}

/** Pure lookup (no React): selected language, then English, then Arabic, then the key itself. */
export function translate<K extends string>(
  dict: ModuleDict<K>,
  lang: Lang,
  key: K,
  params?: Record<string, string | number>
): string {
  const value = (lang === 'en' ? dict.en[key] : dict.ar[key]) ?? dict.en[key] ?? dict.ar[key] ?? key;
  return interpolate(value, params);
}

/**
 * Per-module translations driven by the global language in `useAuthStore` (Arabic by default).
 * Define the dictionary once at module scope: `const dict = { ar: {...}, en: {...} }`.
 */
export function useModuleT<K extends string>(dict: ModuleDict<K>) {
  const lang = useAuthStore((s) => s.lang);
  const t = useCallback<TFn<K>>((key, params) => translate(dict, lang, key, params), [dict, lang]);
  return useMemo(
    () => ({ t, lang, dir: (lang === 'ar' ? 'rtl' : 'ltr') as 'rtl' | 'ltr', isAr: lang === 'ar' }),
    [t, lang]
  );
}
