import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { StructuredTurnCard } from "@/components/groups/structured-turn-card";
import { AgentResponseCard } from "@/components/groups/agent-response-card";
import { AdvisorResponseCard } from "@/components/workforce/advisor-response-card";
import { parseStructuredPayload } from "@/lib/group-payloads";
import type { TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";

function entry(type: TranscriptEntryType, content: string): TranscriptEntry {
  return {
    speakerAgentId: "a1",
    speakerDisplayName: "Ana",
    content,
    phaseIndex: 0,
    phaseName: "Ballot",
    type,
    timestamp: "2026-06-01T00:00:00Z",
    errorReason: null,
    targetAgentId: null,
  } as TranscriptEntry;
}

const BID_BODY =
  '{"bids": [{"subject": "Draft spec", "confidence": 0.9, "estimatedComplexity": "M", "rationale": "I own the API."}]}';
const RETRO_BODY = '{"lessons": [{"lesson": "Timebox the debate", "context": "phase 2 overran"}]}';

/** No `{"`, `[{` or `":` anywhere on screen — the shape of a JSON blob. */
function expectNoRawJson(container: HTMLElement) {
  const text = container.textContent ?? "";
  expect(text).not.toContain('{"');
  expect(text).not.toContain("[{");
  expect(text).not.toContain('":');
}

describe("StructuredTurnCard", () => {
  it("renders a ballot's option, confidence and statement", () => {
    const payload = parseStructuredPayload(
      "VOTE",
      '{"vote": "Option A", "confidence": 0.82, "statement": "Cheaper to run."}',
    )!;
    const { container } = renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(screen.getByTestId("vote-option")).toHaveTextContent("Option A");
    expect(container).toHaveTextContent("82%");
    expect(container).toHaveTextContent("Cheaper to run.");
    expectNoRawJson(container);
  });

  it("says a ballot naming no option will not be counted", () => {
    const payload = parseStructuredPayload("VOTE", '{"statement": "I abstain."}')!;
    renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(screen.getByTestId("vote-no-option")).toBeInTheDocument();
  });

  it("renders every field of a bid, not just its subject", () => {
    const payload = parseStructuredPayload("BID", BID_BODY)!;
    const { container } = renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(container).toHaveTextContent("Draft spec");
    expect(container).toHaveTextContent("M");
    expect(container).toHaveTextContent("90%");
    expect(container).toHaveTextContent("I own the API.");
    expectNoRawJson(container);
  });

  it("says so when a member bid on nothing", () => {
    const payload = parseStructuredPayload("BID", '{"bids": []}')!;
    renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(screen.getByTestId("structured-bid-empty")).toBeInTheDocument();
  });

  it("renders a concession as what was given up for what", () => {
    const payload = parseStructuredPayload(
      "BARGAIN",
      '{"concessions": [{"gaveUp": "price", "inReturnFor": "volume"}]}',
    )!;
    const { container } = renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(container).toHaveTextContent("price");
    expect(container).toHaveTextContent("volume");
    expectNoRawJson(container);
  });

  it("marks an accept that the same turn's counter-proposal supersedes", () => {
    // The backend resolves this contradiction for the counter-proposal, so
    // showing the accept as though it stood would describe a signature the
    // turn walks away from.
    const payload = parseStructuredPayload(
      "BARGAIN",
      '{"accept": "p-1", "proposal": {"terms": "90 up front"}}',
    )!;
    renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(screen.getByTestId("bargain-accept")).toHaveTextContent(/supersede/i);
  });

  it("renders each retro lesson with its context", () => {
    const payload = parseStructuredPayload("RETRO", RETRO_BODY)!;
    const { container } = renderWithProviders(<StructuredTurnCard payload={payload} />);
    expect(screen.getAllByTestId("retro-lesson")).toHaveLength(1);
    expect(container).toHaveTextContent("Timebox the debate");
    expect(container).toHaveTextContent("phase 2 overran");
    expectNoRawJson(container);
  });
});

/**
 * The same four turns, through each transcript renderer that shows them. This
 * codebase has three, and the last time a group feature landed in only some of
 * them a DISSENT rendered as an ordinary opinion on two of the three.
 */
describe("structured turns in the transcript renderers", () => {
  it("the Manager card shows a bid's details, not a subject-only list", () => {
    // `tryParseStructuredItems` matches a BID's `bids` array on its `subject`
    // field and would render just the subjects, silently dropping confidence,
    // complexity and rationale.
    const { container } = renderWithProviders(
      <AgentResponseCard entry={entry("BID", BID_BODY)} />,
    );
    expect(container).toHaveTextContent("I own the API.");
    expect(container).toHaveTextContent("90%");
    expectNoRawJson(container);
  });

  it("the Workforce board card shows a retro harvest rather than its JSON", () => {
    const { container } = renderWithProviders(
      <AdvisorResponseCard
        displayName="Ana"
        agentId="a1"
        content={RETRO_BODY}
        entryType="RETRO"
        boardId="b1"
      />,
    );
    expect(container).toHaveTextContent("Timebox the debate");
    expectNoRawJson(container);
  });

  it("the Workforce board card unwraps a response envelope like the other two", () => {
    // It used to hand `content` straight to ReactMarkdown, so an envelope
    // rendered as its own JSON.
    const { container } = renderWithProviders(
      <AdvisorResponseCard
        displayName="Ana"
        agentId="a1"
        content={JSON.stringify({ output: [{ type: "text", text: "Plain answer." }] })}
        entryType="OPINION"
        boardId="b1"
      />,
    );
    expect(container).toHaveTextContent("Plain answer.");
    expectNoRawJson(container);
  });

  it("an unreadable contract still degrades to prose rather than a blob", () => {
    // Tier 3 of the backend's own parse is "give up"; the UI's equivalent is
    // the generic renderer, which no longer prints raw JSON either.
    const { container } = renderWithProviders(
      <AgentResponseCard entry={entry("VOTE", "I vote for Option A, with reservations.")} />,
    );
    expect(container).toHaveTextContent("I vote for Option A, with reservations.");
  });
});
