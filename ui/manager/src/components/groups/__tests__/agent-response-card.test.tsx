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

describe("AgentResponseCard — untrusted HTML", () => {
  // Agent/LLM output is attacker-influenceable (e.g. via prompt injection). A
  // live <iframe srcdoc> would execute script with full app-origin access, and
  // there is no CSP to fall back on — so raw HTML must never become markup.
  const hostile =
    '**note**\n\n<iframe srcdoc="<script>window.__pwn=1</script>"></iframe>\n\n<img src="https://evil.example/leak.png">';

  it("keeps raw HTML escaped on the default (allowHtml off) path", () => {
    const { container } = renderWithProviders(
      <AgentResponseCard entry={{ ...baseEntry, content: hostile }} />
    );
    expect(container.querySelector("iframe")).toBeNull();
    expect(container.querySelector('img[src*="evil.example"]')).toBeNull();
  });

  it("still strips dangerous HTML on the explicit allowHtml path", () => {
    const { container } = renderWithProviders(
      <AgentResponseCard entry={{ ...baseEntry, content: hostile }} allowHtml />
    );
    expect(container.querySelector("iframe")).toBeNull();
  });
});

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

  it("renders 'No response' for whitespace-only content", () => {
    const whitespaceEntry: TranscriptEntry = {
      ...baseEntry,
      content: "   \n  ",
    };
    renderWithProviders(<AgentResponseCard entry={whitespaceEntry} />);
    expect(screen.getByText("No response")).toBeInTheDocument();
  });

  it("renders emoji verification as structured cards", () => {
    const verificationEntry: TranscriptEntry = {
      ...baseEntry,
      type: "VERIFICATION",
      content:
        "## Task Verification Results\n\n✅ **Financial Analysis**: Passed\nAll requirements met.\n\n❌ **Risk Assessment**: Failed\nNo risk data provided.",
    };
    const { container } = renderWithProviders(
      <AgentResponseCard entry={verificationEntry} />
    );

    expect(screen.getByText("Financial Analysis")).toBeInTheDocument();
    expect(screen.getByText("All requirements met.")).toBeInTheDocument();
    expect(screen.getByText("Risk Assessment")).toBeInTheDocument();
    expect(screen.getByText("No risk data provided.")).toBeInTheDocument();

    expect(container.querySelector("svg.text-emerald-500")).toBeInTheDocument();
    expect(container.querySelector("svg.text-destructive")).toBeInTheDocument();
  });

  it("renders plain text through ReactMarkdown", () => {
    const plainEntry: TranscriptEntry = {
      ...baseEntry,
      content: "Just a plain message without formatting",
    };
    renderWithProviders(<AgentResponseCard entry={plainEntry} />);
    expect(screen.getByText("Just a plain message without formatting")).toBeInTheDocument();
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
