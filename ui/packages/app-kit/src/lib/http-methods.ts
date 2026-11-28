/** Methods that may safely be sent twice (RFC 9110 section 9.2.2). Anything else can repeat a side effect. */
const IDEMPOTENT_METHODS = new Set(["GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE"]);

/** Whether retrying a call with this method can repeat its side effect; an unset method is a GET. */
export function isNonIdempotentMethod(method: string | undefined): boolean {
  const m = (method ?? "GET").trim().toUpperCase();
  return m !== "" && !IDEMPOTENT_METHODS.has(m);
}
