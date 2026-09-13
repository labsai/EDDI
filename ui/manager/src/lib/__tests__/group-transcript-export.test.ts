import { describe, expect, it } from "vitest";
import { generateMarkdown } from "@/lib/group-transcript-export";
import type { GroupConversation, TranscriptEntry } from "@/lib/api/groups";

/**
 * The exporter is one function serving two toolbars that used to have separate
 * copies. These cover the behaviours the surviving copy had to absorb rather
 * than the formatting, which is stable and readable from the source.
 */

function conversation(overrides: Partial<GroupConversation> = {}): GroupConversation {
  return {
    id: "conv-1",
    groupId: "grp-1",
    state: "COMPLETED",
    created: "2026-01-01T00:00:00Z",
    originalQuestion: "Ship it?",
    transcript: [],
    ...overrides,
  } as GroupConversation;
}

function entry(over: Partial<TranscriptEntry>): TranscriptEntry {
  return {
    speakerAgentId: "agent-1",
    speakerDisplayName: "Agent One",
    content: "",
    phaseIndex: 0,
    phaseName: null,
    type: "OPINION",
    timestamp: "2026-01-01T00:00:00Z",
    errorReason: null,
    targetAgentId: null,
    ...over,
  };
}

describe("generateMarkdown", () => {
  it("separates phases, so a multi-phase discussion is not one run of headings", () => {
    const md = generateMarkdown(
      conversation({
        transcript: [
          entry({ content: "first", phaseIndex: 0, phaseName: "Gather" }),
          entry({ content: "second", phaseIndex: 1, phaseName: "Decide" }),
        ],
      }),
    );

    expect(md).toContain("## Phase 1: Gather");
    expect(md).toContain("## Phase 2: Decide");
  });

  it("does not write the final answer twice when a SYNTHESIS entry carried it", () => {
    const md = generateMarkdown(
      conversation({
        synthesizedAnswer: "Ship on Friday.",
        transcript: [
          entry({ type: "SYNTHESIS", content: "Ship on Friday." }),
        ],
      }),
    );

    expect(md.split("Ship on Friday.").length - 1).toBe(1);
    expect(md).not.toContain("Final Answer");
  });

  it("still writes the final answer when the SYNTHESIS entry is empty", () => {
    // An entry with no body renders nothing, so treating its presence alone as
    // "already written" dropped the answer out of the file entirely.
    const md = generateMarkdown(
      conversation({
        synthesizedAnswer: "Ship on Friday.",
        transcript: [entry({ type: "SYNTHESIS", content: "" })],
      }),
    );

    expect(md).toContain("Final Answer");
    expect(md).toContain("Ship on Friday.");
  });

  it("still writes the final answer when the SYNTHESIS entry says something else", () => {
    // A synthesis entry that is not the final answer is a second piece of
    // content, not a duplicate of it. Treating its presence as "already
    // written" dropped the final answer out of the file.
    const md = generateMarkdown(
      conversation({
        synthesizedAnswer: "Ship on Friday.",
        transcript: [entry({ type: "SYNTHESIS", content: "Opinions were split." })],
      }),
    );

    expect(md).toContain("Opinions were split.");
    expect(md).toContain("Final Answer");
    expect(md).toContain("Ship on Friday.");
  });

  it("writes the final answer when there is no SYNTHESIS entry at all", () => {
    const md = generateMarkdown(conversation({ synthesizedAnswer: "Ship on Friday." }));

    expect(md).toContain("Final Answer");
    expect(md).toContain("Ship on Friday.");
  });
});
