/**
 * How long the Branding page waits for one write before it gives the page back.
 * The api client has no abort signal, so the wait is bounded here: the page's
 * one-write-at-a-time guard must never outlive a request that never settles.
 * (A plain object so a test can shorten it.)
 */
export const writeTimeout = { ms: 60_000 };

/** Marks a write abandoned after {@link writeTimeout}; the request itself may still land. */
export class WriteTimeoutError extends Error {
  constructor() {
    super("The write did not settle in time.");
    this.name = "WriteTimeoutError";
  }
}

/** Settles with `work`, or rejects with {@link WriteTimeoutError} once the timeout passes. */
export async function withWriteTimeout<T>(work: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new WriteTimeoutError()), writeTimeout.ms);
  });
  try {
    return await Promise.race([work, timeout]);
  } finally {
    clearTimeout(timer);
  }
}
