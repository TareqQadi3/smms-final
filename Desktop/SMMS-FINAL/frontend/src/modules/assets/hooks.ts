'use client';

import { centerApi } from '@/lib/api';
import { useLoad, type LoadState } from './lib';
import type { CenterRef } from './types';

const fetchCenters = () => centerApi.getAll().then((r) => (r.data ?? []) as CenterRef[]);

/** Centers visible to the caller (the server scopes the list). */
export function useCenters(): LoadState<CenterRef[]> {
  return useLoad(fetchCenters);
}
