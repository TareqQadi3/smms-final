'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loads data with loading/error state. `key` identifies the inputs (change it, or bump `refreshKey`
 * in the caller, to reload). The loader is read through a ref so it may close over props freely.
 */
export function useLoad<T>(loader: () => Promise<T>, key: string) {
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<{ data: T | null; loading: boolean; error: unknown }>({
    data: null, loading: true, error: null,
  });

  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    loaderRef.current()
      .then((data) => { if (alive) setState({ data, loading: false, error: null }); })
      .catch((error: unknown) => { if (alive) setState({ data: null, loading: false, error }); });
    return () => { alive = false; };
  }, [key, tick]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  return { ...state, reload };
}
