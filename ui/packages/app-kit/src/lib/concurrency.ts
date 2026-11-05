/**
 * Run an async mapper over `items` with at most `limit` in flight.
 *
 * The chat picker fans out a deployment-status lookup per agent, and each of
 * those hits one endpoint per environment. `getAgentDescriptors` is called with
 * a page size of 500, so an unbounded `Promise.all` could open ~1000 sockets on
 * one picker refresh — enough to stall the browser's connection pool and make
 * the picker look hung. (It was already unbounded at one request per agent
 * before environments were added; doubling it is what made bounding it
 * non-optional.)
 *
 * Results keep INPUT order regardless of completion order — callers zip them
 * against `items` by index.
 *
 * Rejections are the caller's to handle: pass a mapper that resolves to a
 * result object (as `Promise.allSettled` would) if partial failure is
 * acceptable. A throwing mapper rejects the whole call, deliberately — silently
 * swallowing here would hide a systemic failure as "no agents deployed".
 */
export async function mapWithConcurrency<T, R>(
  items: readonly T[],
  limit: number,
  mapper: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  if (items.length === 0) return [];
  const effectiveLimit = Math.max(1, Math.min(limit, items.length));
  const results = new Array<R>(items.length);
  let next = 0;

  async function worker() {
    for (;;) {
      const index = next++;
      if (index >= items.length) return;
      results[index] = await mapper(items[index]!, index);
    }
  }

  await Promise.all(Array.from({ length: effectiveLimit }, worker));
  return results;
}
