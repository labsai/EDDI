/**
 * What a signed-in app tells the rest of the page when the session ends.
 *
 * Code that keeps per-person state in memory (the Workforce thread registry holds
 * running conversations) must not hand it to the next person. Sign-out is a full
 * page navigation today, which discards it anyway; this makes the rule not depend
 * on that.
 */

type Listener = () => void;
const listeners = new Set<Listener>();

/** Call `listener` when the user signs out. Returns the unsubscribe function. */
export function onSignOut(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Tell every subscriber the session is ending (the auth provider calls this). */
export function emitSignOut(): void {
  for (const listener of [...listeners]) {
    try {
      listener();
    } catch (error) {
      console.error("[session] a sign-out listener threw:", error);
    }
  }
}
