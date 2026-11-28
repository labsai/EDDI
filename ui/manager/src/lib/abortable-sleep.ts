/**
 * Resolves after `ms`, or rejects with an `AbortError` as soon as `signal`
 * aborts (at once if it already has).
 *
 * Removes its abort listener when the timer fires. A polling loop sleeps on one
 * signal every interval, and a listener left behind per call piled up on it —
 * about 200 over the operator's five-minute wait for a resumed turn, every one
 * of them run on abort.
 */
export function abortableSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}
