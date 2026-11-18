let sequence = 0;

/**
 * A client-side id that is unique within the page, for list rows that exist
 * only until the server answers. `Date.now()` alone is not: two files dropped
 * together get the same millisecond, the second row then replaced the first's
 * status, and one ingestion appeared to have finished twice.
 */
export function localId(prefix: string): string {
  sequence += 1;
  return `${prefix}-${Date.now()}-${sequence}`;
}
