import { describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { createInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import type { ReactNode } from "react";
import type { SSEEvent } from "@eddi/core";
import {
  TurnNotesLog,
  guardrailNoteKey,
  parseTurnNote,
  turnNoteKey,
  turnNoteListener,
  MAX_TURN_NOTES_PER_TURN,
} from "./turn-notes";
import { GuardrailNote, TurnNotes, useTurnNotes } from "./TurnNotes";
import { TOOL_ACTIVITY_ENGLISH } from "./i18n";

/*
 * T2.7: a cascade, a re-ask and a guardrail explained in plain words — and
 * only when the stream said so.
 */

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  lng: "en",
  resources: { en: { translation: TOOL_ACTIVITY_ENGLISH } },
  interpolation: { escapeValue: false },
});
const wrap = (ui: ReactNode) => render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);

const ev = (type: SSEEvent["type"], data: unknown): SSEEvent => ({
  type,
  data: typeof data === "string" ? data : JSON.stringify(data),
});

describe("parseTurnNote", () => {
  it("reads a cascade escalation's reason", () => {
    expect(
      parseTurnNote(ev("cascade_escalation", { fromStep: 0, toStep: 1, confidence: 0.4, threshold: 0.7, reason: "low_confidence" })),
    ).toEqual({ kind: "escalation", reason: "low_confidence" });
  });

  it("reads a re-ask's reason", () => {
    expect(parseTurnNote(ev("llm_retry", { reason: "truncated", attempt: 1 }))).toEqual({ kind: "retry", reason: "truncated" });
  });

  it("says nothing for any other event, a malformed payload or a missing reason", () => {
    expect(parseTurnNote(ev("cascade_step_start", { stepIndex: 1 }))).toBeNull();
    expect(parseTurnNote(ev("token", "hello"))).toBeNull();
    expect(parseTurnNote(ev("tool_result", { tool: "x", ok: true, summary: "" }))).toBeNull();
    expect(parseTurnNote(ev("cascade_escalation", "{not json"))).toBeNull();
    expect(parseTurnNote(ev("cascade_escalation", { fromStep: 0, toStep: 1 }))).toBeNull();
    expect(parseTurnNote(ev("llm_retry", { reason: "  " }))).toBeNull();
    expect(parseTurnNote(ev("llm_retry", "null"))).toBeNull();
    // Another event that happens to carry a reason is still not a note.
    expect(parseTurnNote(ev("task_failed", { reason: "low_confidence" }))).toBeNull();
    expect(parseTurnNote(ev("error", { message: "x", reason: "timeout" }))).toBeNull();
  });

  it("a listener hands only notes on", () => {
    const record = vi.fn();
    const listen = turnNoteListener(record);
    listen(ev("token", "x"));
    listen(ev("llm_retry", { reason: "empty", attempt: 1 }));
    expect(record).toHaveBeenCalledTimes(1);
    expect(record).toHaveBeenCalledWith({ kind: "retry", reason: "empty" });
  });
});

describe("turnNoteKey", () => {
  it("words every documented escalation reason, error and retryable_error alike", () => {
    expect(turnNoteKey({ kind: "escalation", reason: "low_confidence" })[1]).toBe(
      "Answered by a larger model because the first answer wasn't confident enough.",
    );
    expect(turnNoteKey({ kind: "escalation", reason: "timeout" })[0]).toBe("turnNotes.escalation.timeout");
    expect(turnNoteKey({ kind: "escalation", reason: "error" })[0]).toBe("turnNotes.escalation.error");
    expect(turnNoteKey({ kind: "escalation", reason: "retryable_error" })[0]).toBe("turnNotes.escalation.error");
    expect(turnNoteKey({ kind: "escalation", reason: "invalid_output" })[0]).toBe("turnNotes.escalation.invalidOutput");
    expect(turnNoteKey({ kind: "escalation", reason: "something_new" })[0]).toBe("turnNotes.escalation.other");
  });

  it("words every documented re-ask reason", () => {
    expect(turnNoteKey({ kind: "retry", reason: "empty" })[0]).toBe("turnNotes.retry.empty");
    expect(turnNoteKey({ kind: "retry", reason: "truncated" })[0]).toBe("turnNotes.retry.truncated");
    expect(turnNoteKey({ kind: "retry", reason: "content_filter" })[0]).toBe("turnNotes.retry.contentFilter");
    expect(turnNoteKey({ kind: "retry", reason: "invalid_json" })[0]).toBe("turnNotes.retry.format");
    expect(turnNoteKey({ kind: "retry", reason: "schema_mismatch" })[0]).toBe("turnNotes.retry.format");
    expect(turnNoteKey({ kind: "retry", reason: "context_too_long" })[0]).toBe("turnNotes.retry.contextTooLong");
    expect(turnNoteKey({ kind: "retry", reason: "?" })[0]).toBe("turnNotes.retry.other");
  });

  it("has an English string for every key it can return", () => {
    const flat = JSON.stringify(TOOL_ACTIVITY_ENGLISH);
    const reasons = {
      escalation: ["low_confidence", "timeout", "error", "invalid_output", "x"],
      retry: ["empty", "truncated", "content_filter", "invalid_json", "context_too_long", "x"],
    } as const;
    for (const [kind, list] of Object.entries(reasons) as ["escalation" | "retry", readonly string[]][]) {
      for (const reason of list) {
        const [key, fallback] = turnNoteKey({ kind, reason });
        expect(flat).toContain(JSON.stringify(fallback).slice(1, -1));
        expect(key.startsWith("turnNotes.")).toBe(true);
      }
    }
  });
});

describe("TurnNotesLog", () => {
  it("files notes per conversation and step, once each, and empties a step a new turn takes", () => {
    const log = new TurnNotesLog();
    log.record("c1", 1, { kind: "escalation", reason: "low_confidence" });
    log.record("c1", 1, { kind: "escalation", reason: "low_confidence" });
    log.record("c1", 2, { kind: "retry", reason: "empty" });
    log.record("c2", 1, { kind: "retry", reason: "truncated" });
    expect(log.turns("c1").get(1)).toEqual([{ kind: "escalation", reason: "low_confidence" }]);
    log.beginTurn("c1", 2);
    expect(log.turns("c1").has(2)).toBe(false);
    expect(log.turns("c1").has(1)).toBe(true);
    log.clearStep("c1", 1);
    expect(log.turns("c1").size).toBe(0);
    expect(log.turns("c2").get(1)).toHaveLength(1);
  });

  it("caps a turn and ignores a null step", () => {
    const log = new TurnNotesLog();
    for (let i = 0; i < MAX_TURN_NOTES_PER_TURN + 5; i++) log.record("c", 1, { kind: "retry", reason: `r${i}` });
    expect(log.turns("c").get(1)).toHaveLength(MAX_TURN_NOTES_PER_TURN);
    log.record("c", null, { kind: "retry", reason: "x" });
    expect(log.turns("c").size).toBe(1);
  });

  it("keeps the same snapshot until something changes", () => {
    const log = new TurnNotesLog();
    const before = log.turns("c");
    log.clearStep("c", 3);
    expect(log.turns("c")).toBe(before);
    log.record("c", 1, { kind: "retry", reason: "empty" });
    expect(log.turns("c")).not.toBe(before);
  });
});

describe("TurnNotes", () => {
  it("renders nothing when the stream said nothing", () => {
    const { container } = wrap(<TurnNotes notes={undefined} />);
    expect(container).toBeEmptyDOMElement();
    wrap(<TurnNotes notes={[]} />);
    expect(screen.queryByTestId("turn-notes")).toBeNull();
  });

  it("renders one plain sentence per note", () => {
    wrap(
      <TurnNotes
        notes={[
          { kind: "escalation", reason: "low_confidence" },
          { kind: "retry", reason: "truncated" },
        ]}
      />,
    );
    const lines = screen.getAllByTestId("turn-note").map((l) => l.textContent);
    expect(lines).toEqual([
      "Answered by a larger model because the first answer wasn't confident enough.",
      "The model was asked again because its first reply was cut off.",
    ]);
    expect(screen.getByTestId("turn-notes")).toHaveAttribute("aria-label", "How this answer was reached");
  });

  it("follows the log as notes arrive", () => {
    const log = new TurnNotesLog();
    function Probe() {
      const turns = useTurnNotes(log, "c");
      return <TurnNotes notes={turns.get(1)} />;
    }
    wrap(<Probe />);
    expect(screen.queryByTestId("turn-notes")).toBeNull();
    act(() => log.record("c", 1, { kind: "escalation", reason: "timeout" }));
    expect(screen.getByTestId("turn-note")).toHaveTextContent("Answered by another model because the first one took too long.");
  });
});

describe("GuardrailNote", () => {
  it("says what a safety check did, naming which one", () => {
    wrap(
      <ul>
        <GuardrailNote notice={{ type: "pii-redaction", phase: "output", action: "redact" }} />
        <GuardrailNote notice={{ type: "prompt-injection", phase: "input", action: "block" }} />
      </ul>,
    );
    expect(screen.getAllByTestId("guardrail-notice").map((l) => l.textContent)).toEqual([
      "A safety check removed part of the answer (pii-redaction).",
      "A safety check stopped your message (prompt-injection).",
    ]);
  });

  it("still says something, with the raw words, for a phase and action it has no sentence for", () => {
    expect(guardrailNoteKey({ type: "t", phase: "mid", action: "quarantine" })[0]).toBe("turnNotes.guardrail.other");
    wrap(
      <ul>
        <GuardrailNote notice={{ type: "t", phase: "mid", action: "quarantine" }} />
      </ul>,
    );
    expect(screen.getByTestId("guardrail-notice")).toHaveTextContent("A safety check acted on this turn (t: mid, quarantine).");
  });
});
