import { describe, it, expect } from "vitest";
import {
  entryBodyToMarkdown,
  interpolateFallback,
  readEntryBody,
  readMessageBody,
} from "@/lib/group-entry-body";
import { parseBargainPayload } from "@/lib/group-payloads";
import { parseTranscriptContent, splitVerdictOutcome } from "@/components/groups/group-utils";

/**
 * The fixtures are what EDDI's engines actually store — copied from the prompt
 * contracts in `DiscussionStylePresets`, `TaskBidEngine`, `NegotiationEngine`
 * and `TaskForceEngine`, and from real stored conversations.
 */
const t = (_key: string, fallback: string, options?: Record<string, unknown>) =>
  interpolateFallback(fallback, options);

const PLAN_FENCED = [
  "```json",
  "[",
  '  {"subject": "Facility Assessment", "description": "Audit the site", "assignedTo": "Energy Engineer", "priority": 1},',
  '  {"subject": "Financial Model", "description": "Build the NPV", "assignedTo": "CFO", "priority": 2}',
  "]",
  "```",
].join("\n");

const VERIFICATION_FENCED = [
  "```json",
  "[",
  '  {"subject": "Facility Assessment", "passed": false, "feedback": "RESULT is empty."},',
  '  {"subject": "Financial Model", "passed": true, "feedback": "Solid."}',
  "]",
  "```",
].join("\n");

const DEBATE_OUTCOME =
  "CON wins (PRO 6/10, CON 8/10) — ### Verdict\nCON built the stronger case.\n\n### Teaching notes\n- **Unit economics** matter.";

describe("readEntryBody", () => {
  it("reads a typed contract as a payload", () => {
    const body = readEntryBody({ type: "VOTE", content: '{"vote": "Ship it", "confidence": 0.8, "statement": "Ready."}' });
    expect(body.kind).toBe("payload");
  });

  it("reads an LLM task plan as items, not as a ```json block", () => {
    const body = readEntryBody({ type: "PLAN", content: PLAN_FENCED });
    expect(body).toMatchObject({ kind: "items", verification: false });
    expect(body.kind === "items" && body.items.map((i) => i.subject)).toEqual(["Facility Assessment", "Financial Model"]);
  });

  it("reads a legacy verification sheet as pass/fail items", () => {
    const body = readEntryBody({ type: "VERIFICATION", content: VERIFICATION_FENCED });
    expect(body).toMatchObject({ kind: "items", verification: true });
  });

  it("reads the backend's pre-formatted ✅/❌ verification", () => {
    const body = readEntryBody({
      type: "VERIFICATION",
      content: "## Task Verification Results\n\n✅ **Financial Model**: Passed\nSolid.\n\n❌ **Facility Assessment**: Failed\nEmpty.",
    });
    expect(body.kind).toBe("items");
  });

  it("expands a pre-configured plan's one-line summary into the configured tasks", () => {
    const body = readEntryBody(
      { type: "PLAN", content: "Pre-configured task plan: 1 tasks" },
      { preConfiguredTasks: [{ subject: "Research", description: "Dig", assignToRole: "ALL", dependsOn: [], priority: 0 }] },
    );
    expect(body.kind === "items" && body.items[0]?.subject).toBe("Research");
    // "ALL" is a placeholder, not a member — seen live as an assignee called ALL.
    expect(body.kind === "items" && body.items[0]?.assignedTo).toBeUndefined();
  });

  it("reads the backend's failure placeholder as a failure, not an answer", () => {
    expect(
      readEntryBody({ type: "SYNTHESIS", content: "[Agent failed to produce output — conversation entered ERROR state]" }),
    ).toEqual({ kind: "failed" });
  });

  it("unwraps a judge's verdict JSON to its reasoning", () => {
    const body = readEntryBody({
      type: "SYNTHESIS",
      content: JSON.stringify({ winner: "CON", scores: { PRO: 6, CON: 8 }, reasoning: "### Verdict\nCON wins." }),
    });
    expect(body.kind).toBe("markdown");
    expect(body.kind === "markdown" && body.text).not.toContain('"winner"');
  });

  it("names a planned task's assignee instead of printing their agent id", () => {
    // Seen live: the planner writes `assignedTo` as the member's agent id.
    const plan = '```json\n[{"subject": "Unit economics", "assignedTo": "6aa82052f1ba61afe9421673", "priority": 1}]\n```';
    const body = readEntryBody({ type: "PLAN", content: plan }, { memberNames: { "6aa82052f1ba61afe9421673": "Operator 1" } });
    expect(body.kind === "items" && body.items[0]?.assignedTo).toBe("Operator 1");
    // No map, or an assignee it does not know: left exactly as written.
    expect(readEntryBody({ type: "PLAN", content: plan }).kind === "items").toBe(true);
  });

  it("reads a human member's literal PASS as an abstention, the way the backend reads an agent's", () => {
    for (const pass of ["PASS", " pass\n", "Pass.", "PASS!"]) {
      expect(readEntryBody({ type: "OPINION", content: pass })).toEqual({ kind: "abstained" });
    }
    // Not the token — a position that merely contains the word.
    expect(readEntryBody({ type: "OPINION", content: "I'll pass on point one, but the timeline is wrong." }).kind).toBe("markdown");
    // Only ONE trailing terminator, as `AbstentionDetector` strips.
    expect(readEntryBody({ type: "OPINION", content: "PASS.." }).kind).toBe("markdown");
    // Where PASS is an answer: a verification verdict, a synthesis.
    expect(readEntryBody({ type: "VERIFICATION", content: "PASS" }).kind).toBe("markdown");
    expect(readEntryBody({ type: "SYNTHESIS", content: "PASS" }).kind).toBe("markdown");
  });

  it("reads an abstention's null body as empty", () => {
    expect(readEntryBody({ type: "ABSTAINED", content: null })).toEqual({ kind: "empty" });
  });
});

describe("debate outcome", () => {
  it("splits the backend's headline from the judge's reasoning", () => {
    expect(splitVerdictOutcome(DEBATE_OUTCOME)).toEqual({
      headline: "CON wins (PRO 6/10, CON 8/10)",
      body: "### Verdict\nCON built the stronger case.\n\n### Teaching notes\n- **Unit economics** matter.",
    });
    expect(splitVerdictOutcome("Tie (PRO 7/10, CON 7/10)")).toEqual({ headline: "Tie (PRO 7/10, CON 7/10)", body: "" });
    expect(splitVerdictOutcome("PRO wins (PRO 7.5/10, CON 6/10) — Better evidence.")?.headline).toBe(
      "PRO wins (PRO 7.5/10, CON 6/10)",
    );
  });

  it("leaves ordinary prose alone", () => {
    expect(splitVerdictOutcome("The panel recommends Germany — for three reasons.")).toBeNull();
  });

  it("renders the synthesized answer with the headline as a lead line and the heading as a heading", () => {
    const text = parseTranscriptContent(DEBATE_OUTCOME);
    expect(text.startsWith("**CON wins (PRO 6/10, CON 8/10)**\n\n### Verdict")).toBe(true);
    expect(text).not.toContain("— ###");
  });
});

describe("readMessageBody (1:1 chat, no entry type)", () => {
  it("reads a member agent's bid sheet", () => {
    expect(
      readMessageBody('{"bids": [{"subject": "Write the migration", "confidence": 0.9, "estimatedComplexity": "M", "rationale": "I own the schema"}]}')?.kind,
    ).toBe("payload");
  });

  it("reads a planner's fenced task list", () => {
    expect(readMessageBody(PLAN_FENCED)?.kind).toBe("items");
  });

  it("reads a bargaining move, whose contract puts its reasoning after the JSON", () => {
    const body = readMessageBody('{"accept": null, "proposal": {"terms": "60/40"}, "concessions": []} I offer this because…');
    expect(body?.kind).toBe("payload");
  });

  it("reads a convergence judge's score as a labelled list", () => {
    const body = readMessageBody('{"agreementScore": 0.82, "converged": true, "summary": "Positions have settled."}');
    expect(body?.kind).toBe("markdown");
    expect(body?.kind === "markdown" && body.text).not.toContain('"agreementScore"');
  });

  it("leaves prose that quotes JSON as prose", () => {
    expect(readMessageBody('Send it like this: {"vote": "A"} and you are done.')).toBeNull();
    expect(readMessageBody("Here is the list:\n```json\n[{\"subject\": \"x\"}]\n```\nThat is all.")).toBeNull();
  });
});

describe("BARGAIN reasoning", () => {
  it("keeps the free-text reasoning the contract asks for after the JSON", () => {
    const payload = parseBargainPayload('{"accept": null, "proposal": {"terms": "60/40"}, "concessions": []} I offer this because…');
    expect(payload?.proposalTerms).toBe("60/40");
    expect(payload?.reasoning).toBe("I offer this because…");
  });

  it("does not treat the closing fence as reasoning", () => {
    expect(parseBargainPayload('My move:\n```json\n{"proposal": {"terms": "50/50 split"}}\n```')?.reasoning).toBeNull();
  });

  it("keeps a move that only explains itself", () => {
    expect(parseBargainPayload('{"accept": null, "proposal": null, "concessions": []} Holding my position.')?.reasoning).toBe(
      "Holding my position.",
    );
  });
});

describe("entryBodyToMarkdown", () => {
  it("writes a bargaining move as readable lines", () => {
    const md = entryBodyToMarkdown(
      readEntryBody({
        type: "BARGAIN",
        content: '{"accept": null, "proposal": {"terms": "55/45 with support"}, "concessions": [{"gaveUp": "weekend support", "inReturnFor": "a longer term"}]} It balances both sides.',
      }),
      t,
    );
    expect(md).toContain("**Counter-proposal:** 55/45 with support");
    expect(md).toContain("- weekend support → in return for a longer term");
    expect(md).toContain("It balances both sides.");
    expect(md).not.toMatch(/"\w+"\s*:/);
  });

  it("writes a ballot with its confidence", () => {
    expect(
      entryBodyToMarkdown(readEntryBody({ type: "VOTE", content: '{"vote": "Ship it", "confidence": 0.8, "statement": "Ready."}' }), t),
    ).toBe("**Ballot:** Ship it (80% confident)\n\nReady.");
  });

  it("writes a plan and a verification sheet as lists", () => {
    expect(entryBodyToMarkdown(readEntryBody({ type: "PLAN", content: PLAN_FENCED }), t)).toBe(
      "1. **Facility Assessment** — Audit the site (Energy Engineer)\n2. **Financial Model** — Build the NPV (CFO)",
    );
    expect(entryBodyToMarkdown(readEntryBody({ type: "VERIFICATION", content: VERIFICATION_FENCED }), t)).toBe(
      "- ❌ **Facility Assessment**: RESULT is empty.\n- ✅ **Financial Model**: Solid.",
    );
  });
});
