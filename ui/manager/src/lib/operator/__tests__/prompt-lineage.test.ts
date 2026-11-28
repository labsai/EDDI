import { describe, it, expect } from "vitest";
import {
  classifyInstructions,
  lineHash,
  normalizeLine,
  PROMPT_LINEAGE_THROUGH_REVISION,
} from "../prompt-lineage";
import { defaultOperatorPromptBody } from "../system-prompt";
import { OPERATOR_REVISION } from "../operator-revision";

/**
 * The line-by-line test the upgrade dialog uses to decide whether stored
 * instructions are the admin's own text or only a trimmed or older default.
 */

/** Lines from a real earlier shipped default (rendered) that today's no longer contains. */
const OLDER_DEFAULT_LINES = [
  "deployment lists nothing. With workspaces enforced, `published` and",
  "switch in the agent's page or the group's panel in the manager.",
  "not_allowed, rate_limited} and eddi_email_ledger_pending.",
];

const NEW_DEFAULT = "Line one.\nLine two.\n\nLine three.";

describe("classifyInstructions", () => {
  it("an identical body adds nothing", () => {
    expect(classifyInstructions(NEW_DEFAULT, NEW_DEFAULT, new Set()).lineage).toBe("only-removes");
  });

  it("a body that only drops lines is 'only-removes', with the missing lines counted", () => {
    const report = classifyInstructions("Line one.\nLine three.", NEW_DEFAULT, new Set());
    expect(report).toEqual({ lineage: "only-removes", foreignLines: 0, olderDefaultLines: 0, missingLines: 1 });
  });

  it("reordered and re-indented lines are still the default's", () => {
    expect(classifyInstructions("   Line three.\nLine   one.", NEW_DEFAULT, new Set()).lineage).toBe("only-removes");
  });

  it("a line from an earlier shipped default is 'older-default'", () => {
    const shipped = new Set([lineHash(normalizeLine("An earlier default's sentence."))]);
    const report = classifyInstructions("Line one.\nAn earlier default's sentence.", NEW_DEFAULT, shipped);
    expect(report.lineage).toBe("older-default");
    expect(report.olderDefaultLines).toBe(1);
  });

  it("any line in no shipped default makes it 'edited' — however short", () => {
    const shipped = new Set([lineHash("An earlier default's sentence.")]);
    expect(classifyInstructions("Line one.\nAn earlier default's sentence.\nBe brief.", NEW_DEFAULT, shipped).lineage).toBe(
      "edited",
    );
    expect(classifyInstructions("Line one.\nok", NEW_DEFAULT, shipped).foreignLines).toBe(1);
  });

  it("blank lines are not additions", () => {
    expect(classifyInstructions("\n\nLine one.\n   \n", NEW_DEFAULT, new Set()).lineage).toBe("only-removes");
  });
});

describe("the generated shipped-default line list", () => {
  it.each(["read_only", "read_write"] as const)(
    "recognises a trimmed %s default as only removing lines",
    (scope) => {
      const trimmed = defaultOperatorPromptBody(scope)
        .split("\n")
        .filter((_, i) => i % 2 === 0)
        .join("\n");
      expect(classifyInstructions(trimmed, defaultOperatorPromptBody(scope)).lineage).toBe("only-removes");
    },
  );

  it("knows the wording of earlier rendered defaults", () => {
    const body = `${OLDER_DEFAULT_LINES.join("\n")}\n${defaultOperatorPromptBody("read_write")}`;
    const report = classifyInstructions(body, defaultOperatorPromptBody("read_write"));
    expect(report.lineage).toBe("older-default");
    expect(report.olderDefaultLines).toBe(OLDER_DEFAULT_LINES.length);
  });

  it("does not mistake an admin's line for a shipped one", () => {
    const body = `${defaultOperatorPromptBody("read_write")}\nAlways answer in French.`;
    expect(classifyInstructions(body, defaultOperatorPromptBody("read_write")).lineage).toBe("edited");
  });

  it("is regenerated in the PR that bumps the revision after the one it last covered", () => {
    // A bump leaves the previous revision's default behind as an "older default".
    // The list must cover it, or operators provisioned at that revision would
    // have their untouched-but-trimmed text read as edits again. Regenerate with
    // `node scripts/gen-operator-prompt-lineage.mjs` (from ui/manager).
    expect(PROMPT_LINEAGE_THROUGH_REVISION).toBeGreaterThanOrEqual(OPERATOR_REVISION - 1);
  });
});
