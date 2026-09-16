import { describe, expect, it } from "vitest";
import type { TFunction } from "i18next";
import { translateStreamError } from "@/hooks/use-chat";

/** A `t` that returns its own fallback, so these assert routing, not wording. */
const t = ((_key: string, fallback: string) => fallback) as unknown as TFunction;

/**
 * Typed error codes on the chat stream.
 *
 * EDDI's `buildKnownConditionOrOpaqueErrorEvent` exists to turn the conditions
 * the streaming endpoint rejects synchronously into machine-readable codes; the
 * non-streaming twin answers a status for the same conditions.
 * `conversation_not_found` is the newest of them — the twin gained a 404 where
 * it used to answer 500 — and without a case here it reached the reader as raw
 * backend English naming an id they cannot act on.
 */
describe("translateStreamError", () => {
  it("turns conversation_not_found into something the reader can act on", () => {
    expect(translateStreamError("conversation_not_found", t)).toMatch(
      /Start a new one/,
    );
  });

  it("covers the other conditions the backend classifies", () => {
    for (const code of [
      "conversation_ended",
      "agent_not_ready",
      "agent_mismatch",
      "quota_accounting_unavailable",
    ]) {
      expect(translateStreamError(code, t)).toBeTruthy();
    }
  });

  it("keeps the backend's own text for a code it does not know", () => {
    // Deliberately not exhaustive over codes EDDI may grow. An unrecognised one
    // falls back to the message it arrived with, which explains more than a
    // generic apology would.
    expect(translateStreamError("some_future_code", t)).toBeNull();
    expect(translateStreamError(undefined, t)).toBeNull();
  });
});
