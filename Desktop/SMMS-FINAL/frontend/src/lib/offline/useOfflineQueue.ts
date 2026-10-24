'use client';

import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { useAuthStore } from '@/store/authStore';
import { subscribeSession } from '@/lib/api';
import { executeItem, reconcileItem } from './executor';
import { ActionQueue, createDefaultStore, type ActionOutcome, type ActionSpec, type QueueItem } from './queue';

let singleton: ActionQueue | null = null;

/** One queue per tab. It keeps running across client-side navigations while the tab is open. */
export function getQueue(): ActionQueue {
  if (!singleton) {
    singleton = new ActionQueue({
      store: createDefaultStore(),
      execute: executeItem,
      reconcile: reconcileItem,
      isOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine !== false),
    });
    if (typeof window !== 'undefined') {
      const kick = () => singleton?.kick();
      window.addEventListener('online', kick);
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') kick();
      });
      // Sign-out (not a pause) forgets the in-memory items; persisted ones return at the next sign-in
      subscribeSession((e) => { if (e.type === 'ended') singleton?.pause(); });
    }
  }
  return singleton;
}

const EMPTY: readonly QueueItem[] = [];

function subscribeOnline(cb: () => void) {
  window.addEventListener('online', cb);
  window.addEventListener('offline', cb);
  return () => {
    window.removeEventListener('online', cb);
    window.removeEventListener('offline', cb);
  };
}

export function useOnlineStatus(): boolean {
  return useSyncExternalStore(
    subscribeOnline,
    () => navigator.onLine !== false,
    () => true
  );
}

/** Starts the queue for the signed-in user and exposes its items. */
export function useOfflineQueue() {
  const userId = useAuthStore((s) => s.user?.id);
  const queue = getQueue();

  useEffect(() => {
    if (userId) void queue.start(userId);
    else if (queue.currentUser()) queue.stop();
  }, [userId, queue]);

  const items = useSyncExternalStore(queue.subscribe, queue.getSnapshot, () => EMPTY);
  const online = useOnlineStatus();

  const enqueue = useCallback((spec: ActionSpec): Promise<ActionOutcome> => queue.enqueue(spec), [queue]);
  const retry = useCallback((id: string) => queue.retry(id), [queue]);
  const discard = useCallback((id: string) => queue.discard(id), [queue]);

  return { items, online, enqueue, retry, discard, queue };
}
