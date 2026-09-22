import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DiscussionOverview } from "../discussion-overview";
import { DiscussionPanel } from "../discussion-panel";
import { bandOrder, rosterIsAnonymous } from "../style-recipe";
import { getStoredDiscussionView, setStoredDiscussionView } from "../discussion-view-mode";
import { buildDigest } from "@/hooks/use-discussion-digest";
import type { DiscussionStyle, GroupConversation, TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";

const A = "agent-architect";
const B = "agent-security";

function entry(
  agentId: string,
  phaseIndex: number,
  type: TranscriptEntryType = "OPINION",
  content = "We should adopt pgvector.",
): TranscriptEntry {
  return {
    speakerAgentId: agentId,
    speakerDisplayName: agentId === A ? "Architect" : "Security",
    content,
    phaseIndex,
    phaseName: phaseIndex === 0 ? "Opinions" : "Synthesis",
    type,
    timestamp: "2026-09-22T10:00:00Z",
    errorReason: null,
    targetAgentId: null,
  };
}

function conversation(overrides: Partial<GroupConversation> = {}): GroupConversation {
  return {
    id: "gc-1",
    groupId: "g-1",
    userId: "u-1",
    state: "COMPLETED",
    originalQuestion: "Should we migrate to pgvector?",
    transcript: [entry(A, 0), entry(B, 0, "CRITIQUE", "Row-level security is unproven.")],
    memberConversationIds: {},
    memberDisplayNames: { [A]: "Architect", [B]: "Security" },
    currentPhaseIndex: 1,
    currentPhaseName: "Synthesis",
    synthesizedAnswer: null,
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    retainedAgentIds: [],
    created: "2026-09-22T10:00:00.000Z",
    lastModified: "2026-09-22T10:05:00.000Z",
    ...overrides,
  } as GroupConversation;
}

function digestFor(style?: DiscussionStyle, overrides: Partial<GroupConversation> = {}) {
  return buildDigest(conversation(overrides), undefined, null, undefined, style ?? null);
}

describe("DiscussionOverview", () => {
  it("renders the headline, rail, roster and matrix for a completed discussion", () => {
    renderWithProviders(<DiscussionOverview digest={digestFor("ROUND_TABLE")} />);

    expect(screen.getByTestId("overview-headline")).toBeInTheDocument();
    expect(screen.getByTestId("overview-question")).toHaveTextContent("Should we migrate to pgvector?");
    expect(screen.getByTestId("overview-phase-rail")).toBeInTheDocument();
    expect(screen.getByTestId("overview-roster")).toBeInTheDocument();
    expect(screen.getByTestId("overview-matrix")).toBeInTheDocument();
  });

  it("shows an empty message rather than a blank frame when there is nothing yet", () => {
    renderWithProviders(<DiscussionOverview digest={buildDigest(null, undefined, null)} />);
    expect(screen.getByTestId("overview-empty")).toBeInTheDocument();
  });

  it("omits the cost stat entirely when nothing was attributed", () => {
    // "$0.00" would read as "this was free" rather than "not measured".
    renderWithProviders(<DiscussionOverview digest={digestFor("ROUND_TABLE")} />);
    expect(screen.queryByText(/Cost:/)).not.toBeInTheDocument();
  });

  it("shows the cost stat once a figure exists", () => {
    const digest = digestFor("ROUND_TABLE", { memberCosts: { [A]: 0.25 }, totalCost: 0.25 });
    renderWithProviders(<DiscussionOverview digest={digest} />);
    expect(screen.getByText(/Cost:/)).toBeInTheDocument();
  });

  it("marks an extracted stance as the member's own words and a generated one as a summary", () => {
    const digest = digestFor("ROUND_TABLE", {
      memberStances: {
        [A]: { text: "Quoted line.", coveredContributions: 2, llmGenerated: false, updated: "2026-09-22T10:01:00Z" },
        [B]: { text: "Paraphrased line.", coveredContributions: 2, llmGenerated: true, updated: "2026-09-22T10:01:00Z" },
      },
    });
    renderWithProviders(<DiscussionOverview digest={digest} />);

    const architect = screen.getByTestId(`overview-member-${A}`);
    expect(within(architect).getByLabelText("Their own words")).toBeInTheDocument();
    const security = screen.getByTestId(`overview-member-${B}`);
    expect(within(security).getByLabelText("Summarised by a model")).toBeInTheDocument();
  });

  it("anonymises the roster for DELPHI and names members otherwise", () => {
    renderWithProviders(<DiscussionOverview digest={digestFor("DELPHI")} />);
    expect(screen.getAllByText(/Participant \d/).length).toBeGreaterThan(0);
    expect(screen.queryByText("Architect")).not.toBeInTheDocument();
  });

  it("names members for a non-anonymous style", () => {
    renderWithProviders(<DiscussionOverview digest={digestFor("ROUND_TABLE")} />);
    expect(screen.getAllByText("Architect").length).toBeGreaterThan(0);
  });

  it("gives every matrix cell a text label, so colour is never the only channel", () => {
    renderWithProviders(<DiscussionOverview digest={digestFor("ROUND_TABLE")} />);
    const matrix = screen.getByTestId("overview-matrix");
    expect(within(matrix).getAllByLabelText(/Architect, Opinions:/).length).toBeGreaterThan(0);
  });

  it("renders the outcome and extras slots where the recipe places them", () => {
    renderWithProviders(
      <DiscussionOverview
        digest={digestFor("DEBATE")}
        outcome={<div data-testid="test-outcome">verdict</div>}
        extras={<div data-testid="test-extras">ledger</div>}
      />,
    );
    expect(screen.getByTestId("test-outcome")).toBeInTheDocument();
    expect(screen.getByTestId("test-extras")).toBeInTheDocument();
  });

  it("shows the synthesised answer even with no structured decision", () => {
    // Most ROUND_TABLE and PEER_REVIEW runs produce no DecisionRecord, and the
    // callers pass only a decision card — so without this the conclusion was
    // reachable only by switching back to the transcript.
    const digest = digestFor("ROUND_TABLE", { synthesizedAnswer: "### Verdict\nAdopt pgvector." });
    renderWithProviders(<DiscussionOverview digest={digest} />);

    expect(screen.getByTestId("overview-synthesis")).toBeInTheDocument();
    expect(screen.getByText("Adopt pgvector.")).toBeInTheDocument();
  });

  it("shows the decision and the synthesis together, neither replacing the other", () => {
    const digest = digestFor("DEBATE", { synthesizedAnswer: "The reasoning." });
    renderWithProviders(
      <DiscussionOverview digest={digest} outcome={<div data-testid="test-outcome">verdict</div>} />,
    );
    expect(screen.getByTestId("test-outcome")).toBeInTheDocument();
    expect(screen.getByTestId("overview-synthesis")).toBeInTheDocument();
  });

  it("renders no outcome band at all when there is neither", () => {
    renderWithProviders(<DiscussionOverview digest={digestFor("ROUND_TABLE")} />);
    expect(screen.queryByTestId("overview-synthesis")).not.toBeInTheDocument();
  });

  it("invokes onSelectPhase from a phase card that has content", () => {
    const onSelectPhase = vi.fn();
    renderWithProviders(
      <DiscussionOverview digest={digestFor("ROUND_TABLE")} onSelectPhase={onSelectPhase} />,
    );
    fireEvent.click(screen.getByTestId("overview-phase-0"));
    expect(onSelectPhase).toHaveBeenCalledWith(0);
  });
});

describe("style-recipe", () => {
  it("leads with the working surface for the two styles that are one", () => {
    expect(bandOrder("TASK_FORCE")[1]).toBe("extras");
    expect(bandOrder("NEGOTIATION")[1]).toBe("extras");
  });

  it("leads a debate with positions rather than progress", () => {
    expect(bandOrder("DEBATE")[1]).toBe("roster");
  });

  it("falls back to the default order for an unknown style", () => {
    // The backend enum can grow ahead of this build; an unknown style must
    // still render something sensible.
    expect(bandOrder("SOMETHING_NEW")).toEqual(bandOrder(null));
    expect(bandOrder(undefined)).toEqual([
      "outcome",
      "phases",
      "roster",
      "interactions",
      "bids",
      "matrix",
      "extras",
    ]);
  });

  it("never repeats a band, and always carries the core four", () => {
    // Not "every band in every recipe": DELPHI deliberately omits one (below).
    // What must hold is that no band renders twice and the four that carry the
    // discussion are always present.
    const styles = ["ROUND_TABLE", "PEER_REVIEW", "DEVIL_ADVOCATE", "DELPHI", "DEBATE", "TASK_FORCE", "NEGOTIATION", "CUSTOM"];
    for (const style of styles) {
      const order = bandOrder(style);
      expect(new Set(order).size, `${style} repeats a band`).toBe(order.length);
      for (const core of ["outcome", "phases", "roster", "matrix"]) {
        expect(order, `${style} is missing ${core}`).toContain(core);
      }
    }
  });

  it("omits the interactions band for DELPHI only", () => {
    // Naming who answered whom would undo the anonymity the method rests on —
    // which is the whole reason DELPHI runs its later rounds ANONYMOUS.
    expect(bandOrder("DELPHI")).not.toContain("interactions");
    for (const style of ["PEER_REVIEW", "DEVIL_ADVOCATE", "DEBATE", "ROUND_TABLE", "CUSTOM"]) {
      expect(bandOrder(style), style).toContain("interactions");
    }
  });

  it("leads the directional styles with who addressed whom", () => {
    expect(bandOrder("PEER_REVIEW")[1]).toBe("interactions");
    expect(bandOrder("DEVIL_ADVOCATE")[1]).toBe("interactions");
  });

  it("anonymises only DELPHI", () => {
    expect(rosterIsAnonymous("DELPHI")).toBe(true);
    expect(rosterIsAnonymous("ROUND_TABLE")).toBe(false);
    expect(rosterIsAnonymous(null)).toBe(false);
  });
});

describe("discussion view mode storage", () => {
  beforeEach(() => localStorage.clear());

  it("defaults to the transcript, which stays the primary view", () => {
    expect(getStoredDiscussionView("group-detail")).toBe("transcript");
  });

  it("round-trips a stored preference", () => {
    setStoredDiscussionView("group-detail", "split");
    expect(getStoredDiscussionView("group-detail")).toBe("split");
  });

  it("keeps surfaces independent", () => {
    setStoredDiscussionView("group-detail", "overview");
    expect(getStoredDiscussionView("workforce-board")).toBe("transcript");
  });

  it("ignores a stored value that is not a mode", () => {
    localStorage.setItem("eddi-discussion-view-group-detail", "card");
    expect(getStoredDiscussionView("group-detail")).toBe("transcript");
  });
});

describe("DiscussionPanel", () => {
  beforeEach(() => localStorage.clear());

  it("shows the transcript by default and never replaces it", () => {
    renderWithProviders(
      <DiscussionPanel
        surface="test"
        conversation={conversation()}
        transcript={<div data-testid="the-transcript">turns</div>}
      />,
    );
    expect(screen.getByTestId("the-transcript")).toBeInTheDocument();
    expect(screen.queryByTestId("discussion-panel-overview")).not.toBeInTheDocument();
  });

  it("switches to the overview and persists the choice", () => {
    renderWithProviders(
      <DiscussionPanel
        surface="test"
        conversation={conversation()}
        transcript={<div data-testid="the-transcript">turns</div>}
      />,
    );
    fireEvent.click(screen.getByTestId("discussion-view-overview"));

    expect(screen.getByTestId("discussion-panel-overview")).toBeInTheDocument();
    expect(screen.queryByTestId("the-transcript")).not.toBeInTheDocument();
    expect(getStoredDiscussionView("test")).toBe("overview");
  });

  it("shows both panels in split mode", () => {
    renderWithProviders(
      <DiscussionPanel
        surface="test"
        conversation={conversation()}
        transcript={<div data-testid="the-transcript">turns</div>}
      />,
    );
    fireEvent.click(screen.getByTestId("discussion-view-split"));

    expect(screen.getByTestId("discussion-split")).toBeInTheDocument();
    expect(screen.getByTestId("the-transcript")).toBeInTheDocument();
    expect(screen.getByTestId("discussion-overview")).toBeInTheDocument();
  });

  it("restores the stored view on mount", () => {
    setStoredDiscussionView("test", "overview");
    renderWithProviders(
      <DiscussionPanel surface="test" conversation={conversation()} transcript={<div>turns</div>} />,
    );
    expect(screen.getByTestId("discussion-panel-overview")).toBeInTheDocument();
  });

  it("returns to the transcript when a phase is picked from the overview", () => {
    // Following a link into a view that does not contain the thing linked to
    // is the classic version of this bug.
    setStoredDiscussionView("test", "overview");
    renderWithProviders(
      <DiscussionPanel
        surface="test"
        conversation={conversation()}
        transcript={<div data-testid="the-transcript">turns</div>}
      />,
    );
    expect(screen.queryByTestId("the-transcript")).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId("overview-phase-0"));

    expect(screen.getByTestId("the-transcript")).toBeInTheDocument();
    expect(getStoredDiscussionView("test")).toBe("transcript");
  });

  it("stays put when a phase is picked in split, where the transcript is already shown", () => {
    setStoredDiscussionView("test", "split");
    renderWithProviders(
      <DiscussionPanel
        surface="test"
        conversation={conversation()}
        transcript={<div data-testid="the-transcript">turns</div>}
      />,
    );
    fireEvent.click(screen.getByTestId("overview-phase-0"));

    expect(screen.getByTestId("discussion-split")).toBeInTheDocument();
    expect(getStoredDiscussionView("test")).toBe("split");
  });

  it("exposes the switch as a radiogroup with arrow-key navigation", () => {
    renderWithProviders(
      <DiscussionPanel surface="test" conversation={conversation()} transcript={<div>turns</div>} />,
    );
    const group = screen.getByTestId("discussion-view-toggle");
    expect(group).toHaveAttribute("role", "radiogroup");

    fireEvent.keyDown(group, { key: "ArrowRight" });
    expect(screen.getByTestId("discussion-view-overview")).toHaveAttribute("aria-checked", "true");
  });

  it("wraps at the ends of the radiogroup", () => {
    renderWithProviders(
      <DiscussionPanel surface="test" conversation={conversation()} transcript={<div>turns</div>} />,
    );
    const group = screen.getByTestId("discussion-view-toggle");
    fireEvent.keyDown(group, { key: "ArrowLeft" });
    expect(screen.getByTestId("discussion-view-split")).toHaveAttribute("aria-checked", "true");
  });
});

/**
 * The text a screen reader announces: everything except `aria-hidden`
 * subtrees. `textContent` alone would include the hidden arrow and make an
 * assertion about spoken output pass whether or not the spoken connector exists.
 */
function spokenText(el: Element): string {
  if (el.getAttribute("aria-hidden") === "true") return "";
  let out = "";
  el.childNodes.forEach((n) => {
    if (n.nodeType === Node.TEXT_NODE) out += n.textContent ?? "";
    else if (n.nodeType === Node.ELEMENT_NODE) out += ` ${spokenText(n as Element)} `;
  });
  return out.replace(/\s+/g, " ").trim();
}

describe("overview accessibility", () => {
  it("roster toggle exposes whether the list is expanded, and which list", () => {
    const ids = Array.from({ length: 10 }, (_, i) => `agent-${i}`);
    const conv = conversation({
      transcript: ids.map((id) => ({ ...entry(A, 0), speakerAgentId: id, speakerDisplayName: id })),
      memberDisplayNames: Object.fromEntries(ids.map((id) => [id, id])),
    });
    renderWithProviders(<DiscussionOverview digest={buildDigest(conv, undefined, null, undefined, "ROUND_TABLE")} />);

    const toggle = screen.getByTestId("overview-roster-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const list = document.getElementById(toggle.getAttribute("aria-controls") ?? "");
    expect(list).not.toBeNull();
    expect(within(list!).getAllByTestId(/^overview-member-/)).toHaveLength(8);

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(within(list!).getAllByTestId(/^overview-member-/)).toHaveLength(10);
  });

  it("an interaction row states its direction in words, not only with an arrow", () => {
    const conv = conversation({
      transcript: [{ ...entry(A, 0, "CRITIQUE", "Row-level security is fine."), targetAgentId: B }],
    });
    renderWithProviders(<DiscussionOverview digest={buildDigest(conv, undefined, null, undefined, "PEER_REVIEW")} />);

    expect(spokenText(screen.getByTestId(`overview-interaction-${A}`))).toBe("Architect addressed Security");
  });
});
