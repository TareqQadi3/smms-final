export interface ParsedError { status?: number; message?: string }

export function parseApiError(e: unknown): ParsedError {
  const r = (e as { response?: { status?: number; data?: { message?: string } } })?.response;
  const m = r?.data?.message;
  return { status: r?.status, message: typeof m === 'string' && m.trim() ? m : (e as { message?: string })?.message };
}

/** 409 = optimistic lock (modified concurrently). The caller offers a reload. */
export const isConflict = (e: unknown) => parseApiError(e).status === 409;

interface ErrorTexts { errForbidden: string; errNotFound: string; conflictReload: string; errGeneric: string }

/** Localized text for an API failure. 400/422 keep the server's explanation; others use fixed texts. */
export function describeError(e: unknown, t: (k: keyof ErrorTexts) => string): string {
  const { status, message } = parseApiError(e);
  if (status === 409) return t('conflictReload');
  if (status === 403) return t('errForbidden');
  if (status === 404) return t('errNotFound');
  if ((status === 400 || status === 422) && message) return message;
  return t('errGeneric');
}
