'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { api, notificationApi } from '@/lib/api';
import { backoffDelay } from '@/lib/offline/backoff';

/**
 * Notification WebSocket client.
 *
 * Browsers cannot set an Authorization header on a WebSocket and a token in the URL would end up in
 * access logs, so: POST /api/ws/ticket (Bearer, single use, 30 s) returns a ticket that is offered as
 * a sub-protocol value: new WebSocket(url, ['smms.v1', 'smms.ticket.<ticket>']). Every connection
 * attempt asks for a fresh ticket. The URL carries nothing secret.
 */

export const WS_SUBPROTOCOL = 'smms.v1';
export const WS_TICKET_PREFIX = 'smms.ticket.';
/** A connection that lived at least this long counts as healthy: the backoff starts over. */
export const STABLE_AFTER_MS = 10_000;
export const POLL_INTERVAL_MS = 60_000;

export interface NotificationFrame {
  type: string;
  title: string;
  /** '' when the notification is not about a work order. */
  workOrderId: string;
  createdAt: string;
}

export type SocketStatus = 'idle' | 'connecting' | 'open' | 'waiting';

export interface SocketLike {
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: { code?: number }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
  close(code?: number): void;
}

export interface NotificationSocketOptions {
  url: string;
  fetchTicket: () => Promise<string>;
  onFrame: (frame: NotificationFrame) => void;
  onStatus?: (status: SocketStatus) => void;
  createSocket?: (url: string, protocols: string[]) => SocketLike;
  now?: () => number;
  random?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (h: unknown) => void;
  baseMs?: number;
  maxMs?: number;
}

export class NotificationSocket {
  private socket: SocketLike | null = null;
  private timer: unknown = null;
  private attempt = 0;
  private openedAt: number | null = null;
  private running = false;
  private generation = 0;
  private status: SocketStatus = 'idle';

  constructor(private readonly opts: NotificationSocketOptions) {}

  getStatus(): SocketStatus { return this.status; }
  getAttempt(): number { return this.attempt; }

  private setStatus(s: SocketStatus) {
    if (this.status === s) return;
    this.status = s;
    this.opts.onStatus?.(s);
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.attempt = 0;
    void this.connect();
  }

  /** Reconnect now (network came back, tab visible again) unless already connected. */
  nudge(): void {
    if (!this.running || this.status === 'open' || this.status === 'connecting') return;
    this.clearTimer();
    this.attempt = 0;
    void this.connect();
  }

  stop(): void {
    this.running = false;
    this.generation++;
    this.clearTimer();
    const s = this.socket;
    this.socket = null;
    if (s) {
      s.onopen = s.onmessage = s.onclose = s.onerror = null;
      try { s.close(1000); } catch { /* already closed */ }
    }
    this.setStatus('idle');
  }

  private clearTimer() {
    if (this.timer != null) {
      (this.opts.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>)))(this.timer);
      this.timer = null;
    }
  }

  private scheduleReconnect() {
    if (!this.running) return;
    this.setStatus('waiting');
    const delay = backoffDelay(this.attempt, {
      baseMs: this.opts.baseMs ?? 1_000, maxMs: this.opts.maxMs ?? 60_000, random: this.opts.random,
    });
    this.attempt += 1;
    this.clearTimer();
    this.timer = (this.opts.setTimer ?? ((fn, ms) => setTimeout(fn, ms)))(() => {
      this.timer = null;
      void this.connect();
    }, delay);
  }

  private async connect(): Promise<void> {
    if (!this.running) return;
    const gen = ++this.generation;
    this.setStatus('connecting');
    let ticket: string;
    try {
      ticket = await this.opts.fetchTicket();
    } catch (e) {
      if (gen !== this.generation) return;
      // 401 after the API layer's refresh attempt: the session is over, so stop (the app redirects to /login)
      if (axios.isAxiosError(e) && e.response?.status === 401) {
        this.running = false;
        this.setStatus('idle');
        return;
      }
      this.scheduleReconnect();
      return;
    }
    if (gen !== this.generation || !this.running) return;

    const create = this.opts.createSocket ?? ((url, protocols) => new WebSocket(url, protocols) as unknown as SocketLike);
    let socket: SocketLike;
    try {
      socket = create(this.opts.url, [WS_SUBPROTOCOL, WS_TICKET_PREFIX + ticket]);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      if (gen !== this.generation) return;
      this.openedAt = (this.opts.now ?? Date.now)();
      this.setStatus('open');
    };
    socket.onmessage = (ev) => {
      if (gen !== this.generation) return;
      const frame = parseFrame(ev.data);
      if (frame) this.opts.onFrame(frame);
    };
    const onEnd = () => {
      if (gen !== this.generation) return;
      this.socket = null;
      const lived = this.openedAt !== null ? (this.opts.now ?? Date.now)() - this.openedAt : 0;
      if (lived >= STABLE_AFTER_MS) this.attempt = 0;
      this.openedAt = null;
      this.scheduleReconnect();
    };
    socket.onclose = onEnd;
    socket.onerror = () => { /* a close event always follows */ };
  }
}

export function parseFrame(data: unknown): NotificationFrame | null {
  if (typeof data !== 'string') return null;
  try {
    const v = JSON.parse(data) as Partial<NotificationFrame>;
    if (!v || typeof v.type !== 'string' || typeof v.title !== 'string') return null;
    return {
      type: v.type,
      title: v.title,
      workOrderId: typeof v.workOrderId === 'string' ? v.workOrderId : '',
      createdAt: typeof v.createdAt === 'string' ? v.createdAt : '',
    };
  } catch {
    return null;
  }
}

export function defaultSocketUrl(): string {
  const env = process.env.NEXT_PUBLIC_WS_URL;
  if (env) return env;
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${window.location.host}/ws/notifications`;
}

export async function fetchWsTicket(): Promise<string> {
  const res = await api.post<{ ticket?: string }>('/ws/ticket', null, { timeout: 10_000 });
  if (!res.data?.ticket) throw new Error('no ticket');
  return res.data.ticket;
}

export interface UseNotificationFeed {
  unread: number;
  /** True while the WebSocket is open; otherwise unread-count is polled every 60 s. */
  live: boolean;
  refresh: () => void;
  markAllRead: () => Promise<void>;
}

/**
 * Unread counter fed by the WebSocket, with polling as the fallback while it is not connected.
 * `onFrame` is called for every pushed notification (e.g. to refresh the queue).
 */
export function useNotificationFeed(enabled: boolean, onFrame?: (f: NotificationFrame) => void): UseNotificationFeed {
  const [unread, setUnread] = useState(0);
  const [live, setLive] = useState(false);
  const frameRef = useRef(onFrame);
  frameRef.current = onFrame;

  const refresh = useCallback(() => {
    notificationApi.unreadCount().then((r) => setUnread(Number(r.data) || 0)).catch(() => {});
  }, []);

  useEffect(() => {
    if (!enabled) return;
    refresh();
    const socket = new NotificationSocket({
      url: defaultSocketUrl(),
      fetchTicket: fetchWsTicket,
      onFrame: (f) => { setUnread((n) => n + 1); frameRef.current?.(f); },
      onStatus: (s) => { setLive(s === 'open'); if (s === 'open') refresh(); },
    });
    socket.start();
    const poll = setInterval(() => { if (socket.getStatus() !== 'open') refresh(); }, POLL_INTERVAL_MS);
    const nudge = () => socket.nudge();
    const visible = () => { if (document.visibilityState === 'visible') { nudge(); refresh(); } };
    window.addEventListener('online', nudge);
    document.addEventListener('visibilitychange', visible);
    return () => {
      clearInterval(poll);
      window.removeEventListener('online', nudge);
      document.removeEventListener('visibilitychange', visible);
      socket.stop();
    };
  }, [enabled, refresh]);

  const markAllRead = useCallback(async () => {
    await notificationApi.readAll();
    setUnread(0);
  }, []);

  return { unread, live, refresh, markAllRead };
}
