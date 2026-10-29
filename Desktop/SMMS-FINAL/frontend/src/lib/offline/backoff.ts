/** Exponential backoff with jitter, shared by the offline queue and the WebSocket client. */
export interface BackoffOptions {
  baseMs?: number;
  maxMs?: number;
  /** 0..1, injectable for tests. Jitter spreads the delay over [50%, 100%] of the exponential value. */
  random?: () => number;
}

/** Delay before attempt number `attempt` (0 = first retry): base * 2^attempt, capped, jittered. */
export function backoffDelay(attempt: number, opts: BackoffOptions = {}): number {
  const base = opts.baseMs ?? 1_000;
  const max = opts.maxMs ?? 60_000;
  const rnd = opts.random ?? Math.random;
  const exp = Math.min(max, base * 2 ** Math.max(0, Math.min(attempt, 30)));
  return Math.round(exp * (0.5 + rnd() * 0.5));
}
