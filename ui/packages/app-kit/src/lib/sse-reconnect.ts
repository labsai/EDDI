import {
  SSE_RECONNECT_BASE_MS,
  SSE_RECONNECT_MAX_ATTEMPTS,
  SSE_RECONNECT_MAX_DELAY_MS,
} from "./constants";

/**
 * The one SSE reconnect policy: exponential backoff, capped delay, capped
 * attempts.
 *
 * ## What this replaces
 *
 * Four separate `setTimeout(connect, 5000)` loops — `BearerEventSource`,
 * `session-log-store`, `use-logs` and `use-coordinator` — none of them bounded.
 * A stream the server will never serve (no `eddi-admin` role, so
 * `/administration/logs` answers 403) was retried every five seconds for the
 * lifetime of the session, and `session-log-store` starts at app boot, so it did
 * that whether or not the user ever opened the Logs page.
 *
 * Meanwhile `constants.ts` had exported `SSE_RECONNECT_BASE_MS`,
 * `SSE_RECONNECT_MAX_ATTEMPTS` and `SSE_RECONNECT_MAX_DELAY_MS` since forever,
 * describing exactly this policy, and nothing imported them. The doc comment
 * even referenced `MAX_SSE_RECONNECT_ATTEMPTS`, a name that never existed. This
 * module makes the constants real.
 *
 * ## The policy
 *
 * Attempt n waits `min(BASE * 2^n, MAX_DELAY)`, so 5s, 10s, 20s, 40s, 60s, 60s…
 * and stops after `MAX_ATTEMPTS`. Giving up matters as much as backing off: a
 * 403 is not going to become a 200, and a client that keeps asking is just noise
 * in someone's access log.
 */

/** How long to wait before attempt `attempt` (0-based). */
export function reconnectDelayMs(attempt: number): number {
  return Math.min(SSE_RECONNECT_BASE_MS * 2 ** attempt, SSE_RECONNECT_MAX_DELAY_MS);
}

export interface ReconnectScheduler {
  /**
   * Queue the next attempt. Returns false — and schedules nothing — once the
   * attempt budget is spent, so callers can surface a terminal state.
   */
  schedule(): boolean;
  /** Call on a successful connection: the budget refills. */
  reset(): void;
  /** Call on teardown: cancels any pending attempt. */
  cancel(): void;
  /** Attempts used since the last {@link reset}. Exposed for tests. */
  readonly attempts: number;
}

/**
 * Build a scheduler that calls `connect` on the policy above.
 *
 * `reset()` on every successful open is what makes a long-lived stream that
 * blips occasionally behave differently from one that is simply refused: the
 * former never walks up the backoff curve, the latter reaches the cap and stops.
 */
export function createReconnectScheduler(connect: () => void): ReconnectScheduler {
  let attempts = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;

  return {
    get attempts() {
      return attempts;
    },
    schedule() {
      if (attempts >= SSE_RECONNECT_MAX_ATTEMPTS) return false;
      const delay = reconnectDelayMs(attempts);
      attempts++;
      if (timer !== null) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        connect();
      }, delay);
      return true;
    },
    reset() {
      attempts = 0;
    },
    cancel() {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    },
  };
}
