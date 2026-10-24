import { AxiosError } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { NotificationSocket, STABLE_AFTER_MS, WS_SUBPROTOCOL, parseFrame, type SocketLike, type SocketStatus } from './ws';

class FakeSocket implements SocketLike {
  onopen: SocketLike['onopen'] = null;
  onmessage: SocketLike['onmessage'] = null;
  onclose: SocketLike['onclose'] = null;
  onerror: SocketLike['onerror'] = null;
  closed = false;
  constructor(public url: string, public protocols: string[]) {}
  close() { this.closed = true; }
}

function harness(fetchTicket?: () => Promise<string>) {
  const clock = { t: 0 };
  const timers: { fn: () => void; ms: number }[] = [];
  const sockets: FakeSocket[] = [];
  const statuses: SocketStatus[] = [];
  const frames: unknown[] = [];
  let n = 0;
  const ticketFn = fetchTicket ?? (async () => `T${++n}`);
  const ws = new NotificationSocket({
    url: 'wss://example.test/ws/notifications',
    fetchTicket: ticketFn,
    onFrame: (f) => frames.push(f),
    onStatus: (s) => statuses.push(s),
    createSocket: (url, protocols) => { const s = new FakeSocket(url, protocols); sockets.push(s); return s; },
    now: () => clock.t,
    random: () => 1,
    setTimer: (fn, ms) => { const h = { fn, ms }; timers.push(h); return h; },
    clearTimer: (h) => { const i = timers.indexOf(h as (typeof timers)[number]); if (i >= 0) timers.splice(i, 1); },
  });
  const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };
  return { ws, clock, timers, sockets, statuses, frames, flush };
}

describe('NotificationSocket', () => {
  it('offers the subprotocol flow with a fresh ticket and nothing secret in the URL', async () => {
    const h = harness();
    h.ws.start();
    await h.flush();
    expect(h.sockets).toHaveLength(1);
    expect(h.sockets[0].protocols).toEqual([WS_SUBPROTOCOL, 'smms.ticket.T1']);
    expect(h.sockets[0].url).toBe('wss://example.test/ws/notifications');
    expect(h.sockets[0].url).not.toContain('T1');
    h.sockets[0].onopen?.({});
    expect(h.ws.getStatus()).toBe('open');
  });

  it('reconnects with exponential backoff and a NEW ticket each time', async () => {
    const h = harness();
    h.ws.start();
    await h.flush();
    // connection dies before it was ever healthy: 1 s, 2 s, 4 s, 8 s
    const delays: number[] = [];
    for (let i = 0; i < 4; i++) {
      h.sockets.at(-1)!.onopen?.({});
      h.clock.t += 1_000; // far below STABLE_AFTER_MS
      h.sockets.at(-1)!.onclose?.({ code: 1006 });
      expect(h.ws.getStatus()).toBe('waiting');
      const t = h.timers.pop()!;
      delays.push(t.ms);
      t.fn();
      await h.flush();
    }
    expect(delays).toEqual([1000, 2000, 4000, 8000]);
    expect(h.sockets.map((s) => s.protocols[1])).toEqual(['smms.ticket.T1', 'smms.ticket.T2', 'smms.ticket.T3', 'smms.ticket.T4', 'smms.ticket.T5']);
  });

  it('caps the delay at 60 s and starts over after a stable connection', async () => {
    const h = harness();
    h.ws.start();
    await h.flush();
    for (let i = 0; i < 9; i++) {
      h.sockets.at(-1)!.onclose?.({ code: 1006 }); // never opened
      h.timers.pop()!.fn();
      await h.flush();
    }
    h.sockets.at(-1)!.onclose?.({});
    expect(h.timers.at(-1)!.ms).toBe(60_000);
    h.timers.pop()!.fn();
    await h.flush();

    h.sockets.at(-1)!.onopen?.({});
    h.clock.t += STABLE_AFTER_MS + 1;
    h.sockets.at(-1)!.onclose?.({});
    expect(h.timers.at(-1)!.ms).toBe(1000); // healthy connection: backoff reset
  });

  it('stop() closes the socket and never reconnects', async () => {
    const h = harness();
    h.ws.start();
    await h.flush();
    const s = h.sockets[0];
    h.ws.stop();
    expect(s.closed).toBe(true);
    expect(h.timers).toHaveLength(0);
    expect(h.ws.getStatus()).toBe('idle');
  });

  it('retries with backoff when the ticket request fails, and stops for good on 401', async () => {
    let calls = 0;
    const h = harness(async () => {
      calls++;
      if (calls === 1) throw new AxiosError('Network Error', 'ERR_NETWORK');
      const config = { headers: {} } as never;
      throw new AxiosError('x', 'E', config, null, { status: 401, data: {}, statusText: '', headers: {}, config });
    });
    h.ws.start();
    await h.flush();
    expect(h.sockets).toHaveLength(0);
    expect(h.timers).toHaveLength(1);
    h.timers.pop()!.fn();
    await h.flush();
    expect(calls).toBe(2);
    expect(h.timers).toHaveLength(0); // 401: session over, no more attempts
    expect(h.ws.getStatus()).toBe('idle');
  });

  it('delivers parsed frames and ignores garbage', async () => {
    const h = harness();
    h.ws.start();
    await h.flush();
    const s = h.sockets[0];
    s.onmessage?.({ data: JSON.stringify({ type: 'ASSIGNED', title: 'New job', workOrderId: 'wo1', createdAt: '2026-01-01T00:00:00Z' }) });
    s.onmessage?.({ data: 'not json' });
    s.onmessage?.({ data: JSON.stringify({ nope: 1 }) });
    expect(h.frames).toEqual([{ type: 'ASSIGNED', title: 'New job', workOrderId: 'wo1', createdAt: '2026-01-01T00:00:00Z' }]);
  });

  it('nudge() reconnects immediately from the waiting state', async () => {
    const h = harness();
    h.ws.start();
    await h.flush();
    h.sockets[0].onclose?.({});
    expect(h.timers).toHaveLength(1);
    h.ws.nudge();
    await h.flush();
    expect(h.timers).toHaveLength(0);
    expect(h.sockets).toHaveLength(2);
  });

  it('parseFrame defaults a missing workOrderId to an empty string', () => {
    expect(parseFrame('{"type":"X","title":"t"}')).toEqual({ type: 'X', title: 't', workOrderId: '', createdAt: '' });
    expect(parseFrame(42)).toBeNull();
    vi.fn();
  });
});
