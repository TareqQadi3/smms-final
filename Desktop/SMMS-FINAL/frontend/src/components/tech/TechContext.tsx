'use client';

import { createContext, useContext } from 'react';

export interface TechContextValue {
  unread: number;
  live: boolean;
  /** Increments for every pushed notification; screens refetch when it changes. */
  frameTick: number;
  refreshUnread: () => void;
  markAllRead: () => Promise<void>;
}

export const TechContext = createContext<TechContextValue>({
  unread: 0,
  live: false,
  frameTick: 0,
  refreshUnread: () => {},
  markAllRead: async () => {},
});

export const useTech = () => useContext(TechContext);
