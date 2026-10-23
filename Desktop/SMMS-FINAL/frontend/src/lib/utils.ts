import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Locale-aware date formatting (Gregorian calendar, Latin digits so numbers match
 * the rest of the UI). ar -> ar-SA, en -> en-GB. Replaces date-fns format() with no locale.
 */
export function formatDate(
  value: string | number | Date | null | undefined,
  lang: 'ar' | 'en',
  withTime = false,
  fallback = '-'
): string {
  if (!value) return fallback
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return fallback
  const locale = lang === 'ar' ? 'ar-SA-u-ca-gregory-nu-latn' : 'en-GB'
  return new Intl.DateTimeFormat(locale, {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    ...(withTime ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}),
  }).format(d)
}
