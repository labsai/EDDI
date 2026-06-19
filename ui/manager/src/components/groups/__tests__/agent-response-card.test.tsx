import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { AgentResponseCard } from "@/components/groups/agent-response-card";
import type { TranscriptEntry } from "@/lib/api/groups";

const baseEntry: TranscriptEntry = {
  speakerAgentId: "agent-001",
  speakerDisplayName: "Review Agent",
  type: "OPINION",
  content: "I think this product is good.",
  timestamp: "2024-06-01T10:30:00Z",
  phaseIndex: 0,
  phaseName: null,
  errorReason: null,
  targetAgentId: null,
};

describe("AgentResponseCard", () => {
  it("renders the speaker name", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    expect(screen.getByText("Review Agent")).toBeInTheDocument();
  });

  it("renders the content", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    expect(screen.getByText("I think this product is good.")).toBeInTheDocument();
  });

  it("renders type badge", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    // OPINION badge label from ENTRY_TYPE_INFO
    expect(screen.getByText("Opinion")).toBeInTheDocument();
  });

  it("shows initials in avatar", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    // getInitials("Review Agent") → "RA"
    expect(screen.getByText("RA")).toBeInTheDocument();
  });

  it("renders timestamp", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    // safeFormatDate with "time" style — some time string
    const timeElements = document.querySelectorAll("span.text-\\[10px\\]");
    expect(timeElements.length).toBeGreaterThan(0);
  });

  it("shows typing indicator when isSpeaking", () => {
    renderWithProviders(
      <AgentResponseCard entry={baseEntry} isSpeaking />
    );
    expect(screen.getByText("responding…")).toBeInTheDocument();
  });

  it("does not show content when isSpeaking", () => {
    renderWithProviders(
      <AgentResponseCard entry={baseEntry} isSpeaking />
    );
    expect(screen.queryByText("I think this product is good.")).not.toBeInTheDocument();
  });

  it("renders 'No response' for entry with no content and no error", () => {
    const emptyEntry: TranscriptEntry = {
      ...baseEntry,
      content: "",
    };
    renderWithProviders(<AgentResponseCard entry={emptyEntry} />);
    expect(screen.getByText("No response")).toBeInTheDocument();
  });

  it("renders error reason for ERROR type", () => {
    const errorEntry: TranscriptEntry = {
      ...baseEntry,
      type: "ERROR",
      content: "",
      errorReason: "Timeout after 60s",
    };
    renderWithProviders(<AgentResponseCard entry={errorEntry} />);
    expect(screen.getByText("Timeout after 60s")).toBeInTheDocument();
    expect(screen.getByText(/Error/)).toBeInTheDocument();
  });

  it("renders SKIPPED type with skipped label", () => {
    const skippedEntry: TranscriptEntry = {
      ...baseEntry,
      type: "SKIPPED",
      content: "",
      errorReason: "Agent unavailable",
    };
    renderWithProviders(<AgentResponseCard entry={skippedEntry} />);
    expect(screen.getByText("Agent unavailable")).toBeInTheDocument();
    const skippedElements = screen.getAllByText(/Skipped/);
    expect(skippedElements.length).toBeGreaterThanOrEqual(1);
  });

  it("applies special styling for SYNTHESIS type", () => {
    const synthesisEntry: TranscriptEntry = {
      ...baseEntry,
      type: "SYNTHESIS",
      content: "Final synthesis of all opinions.",
    };
    const { container } = renderWithProviders(
      <AgentResponseCard entry={synthesisEntry} />
    );
    const card = container.querySelector("[data-testid]");
    expect(card?.className).toContain("border-primary/40");
  });

  it("applies opacity for ERROR type", () => {
    const errorEntry: TranscriptEntry = {
      ...baseEntry,
      type: "ERROR",
      content: "",
      errorReason: "Failed",
    };
    const { container } = renderWithProviders(
      <AgentResponseCard entry={errorEntry} />
    );
    const card = container.querySelector("[data-testid]");
    expect(card?.className).toContain("opacity-60");
  });

  it("shows target agent truncated when present", () => {
    const targeted: TranscriptEntry = {
      ...baseEntry,
      targetAgentId: "target-agent-long-id",
    };
    renderWithProviders(<AgentResponseCard entry={targeted} />);
    // Shows first 8 chars + "…"
    expect(screen.getByText("→ target-a…")).toBeInTheDocument();
  });

  it("does not show target agent when not present", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    // No "→" text
    const arrows = screen.queryByText(/→/);
    expect(arrows).not.toBeInTheDocument();
  });

  it("renders markdown content for markdown strings", () => {
    const mdEntry: TranscriptEntry = {
      ...baseEntry,
      content: "## Heading\n\n**Bold text** and *italic*",
    };
    renderWithProviders(<AgentResponseCard entry={mdEntry} />);
    // ReactMarkdown should render heading
    expect(screen.getByText("Heading")).toBeInTheDocument();
  });

  it("renders plain text for non-markdown content", () => {
    const plainEntry: TranscriptEntry = {
      ...baseEntry,
      content: "Just a plain message without formatting",
    };
    renderWithProviders(<AgentResponseCard entry={plainEntry} />);
    expect(screen.getByText("Just a plain message without formatting")).toBeInTheDocument();
  });

  it("has correct data-testid based on speaker and phase", () => {
    renderWithProviders(<AgentResponseCard entry={baseEntry} />);
    expect(
      screen.getByTestId("transcript-entry-agent-001-0")
    ).toBeInTheDocument();
  });
});
