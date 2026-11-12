import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import type { ReactNode } from "react";
import type { DecisionRecord, SharedArtifact } from "@eddi/core/groups";
import { TEAM_ENGLISH } from "./i18n";
import { ArtifactVersions } from "./artifact-versions";
import { artifactVersions } from "./artifact-version-list";
import { decisionSummary } from "./decision-summary";
import { DecisionRecordCard } from "./decision-record-card";
import { HumanTurnBanner } from "./human-turn-banner";

/*
 * Tier 2 of the frontend unification plan, the team kind: artifacts with their
 * versions (T2.6), the decision in plain words and the human member's turn
 * (T2.1).
 */

const i18n = createInstance();
void i18n.use(initReactI18next).init({
  lng: "en",
  resources: { en: { translation: TEAM_ENGLISH } },
  interpolation: { escapeValue: false },
});
const wrap = (ui: ReactNode) => render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);

const artifact = (over: Partial<SharedArtifact> = {}): SharedArtifact => ({
  id: "a1",
  groupConversationId: "gc1",
  ownerUserId: "u1",
  name: "Launch plan",
  type: "MARKDOWN",
  content: "## Plan v3\n\nShip on Friday.",
  version: 3,
  lastEditorAgentId: "agent-editor",
  status: "DRAFT",
  history: [
    { content: "## Plan v1", editorAgentId: "agent-writer", version: 1, at: "2026-11-01T10:00:00Z" },
    { content: "## Plan v2 <img src=x onerror=alert(1)>", editorAgentId: "agent-editor", version: 2, at: "2026-11-02T10:00:00Z" },
  ],
  createdAt: "2026-11-01T10:00:00Z",
  updatedAt: "2026-11-03T10:00:00Z",
  ...over,
});

describe("artifactVersions", () => {
  it("lists the current version first, then the kept revisions newest first, each number once", () => {
    const versions = artifactVersions(
      artifact({
        history: [
          { content: "one", editorAgentId: "w", version: 1, at: "t1" },
          { content: "two", editorAgentId: "e", version: 2, at: "t2" },
          { content: "dup of current", editorAgentId: "e", version: 3, at: "t3" },
        ],
      }),
    );
    expect(versions.map((v) => v.version)).toEqual([3, 2, 1]);
    expect(versions[0]!.content).toBe("## Plan v3\n\nShip on Friday.");
    expect(versions[0]!.at).toBe("2026-11-03T10:00:00Z");
  });

  it("copes with an artifact that carries no history", () => {
    expect(artifactVersions(artifact({ history: undefined as unknown as [] })).map((v) => v.version)).toEqual([3]);
  });
});

describe("ArtifactVersions", () => {
  it("opens a single artifact on its current version", () => {
    wrap(<ArtifactVersions artifacts={[artifact()]} memberDisplayNames={{ "agent-editor": "Editor" }} />);
    expect(screen.getByTestId("artifact-detail-a1")).toBeInTheDocument();
    expect(screen.getByTestId("artifact-version-content")).toHaveTextContent("Plan v3");
    expect(screen.queryByTestId("artifact-older-version")).toBeNull();
    expect(screen.getByTestId("artifact-version-meta")).toHaveTextContent("Written by Editor on");
  });

  it("switches to an earlier version and back, saying which one is shown", () => {
    wrap(<ArtifactVersions artifacts={[artifact()]} memberDisplayNames={{ "agent-writer": "Writer" }} />);
    const select = screen.getByTestId("artifact-version-select") as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(["3", "2", "1"]);
    expect(select.options[0]!.textContent).toContain("v3 (current)");

    fireEvent.change(select, { target: { value: "1" } });
    expect(screen.getByTestId("artifact-version-content")).toHaveTextContent("Plan v1");
    expect(screen.getByTestId("artifact-version-content")).not.toHaveTextContent("Ship on Friday");
    expect(screen.getByTestId("artifact-older-version")).toHaveTextContent(
      "This is an earlier version (v1). The current one is v3.",
    );
    expect(screen.getByTestId("artifact-version-meta")).toHaveTextContent("Written by Writer on");

    fireEvent.click(screen.getByTestId("artifact-show-current"));
    expect(select.value).toBe("3");
    expect(screen.getByTestId("artifact-version-content")).toHaveTextContent("Ship on Friday.");
    expect(screen.queryByTestId("artifact-older-version")).toBeNull();
  });

  it("renders a markdown version through the one renderer with raw HTML escaped", () => {
    wrap(<ArtifactVersions artifacts={[artifact()]} />);
    fireEvent.change(screen.getByTestId("artifact-version-select"), { target: { value: "2" } });
    const content = screen.getByTestId("artifact-version-content");
    expect(content.querySelector("img")).toBeNull();
    expect(content).toHaveTextContent("<img src=x onerror=alert(1)>");
  });

  it("lists several artifacts and goes back to the list", () => {
    wrap(
      <ArtifactVersions
        artifacts={[artifact(), artifact({ id: "a2", name: "Budget", type: "JSON", content: '{"total":3}', version: 1, history: [] })]}
      />,
    );
    const list = screen.getByTestId("artifact-versions-list");
    expect(within(list).getAllByRole("button")).toHaveLength(2);
    fireEvent.click(screen.getByTestId("artifact-open-a2"));
    expect(screen.getByTestId("artifact-version-content").tagName).toBe("PRE");
    expect(screen.getByTestId("artifact-version-content")).toHaveTextContent('"total": 3');
    fireEvent.click(screen.getByTestId("artifact-versions-back"));
    expect(screen.getByTestId("artifact-versions-list")).toBeInTheDocument();
  });

  it("says when older versions were dropped", () => {
    wrap(
      <ArtifactVersions
        artifacts={[
          artifact({
            version: 14,
            history: [{ content: "x", editorAgentId: "e", version: 13, at: "t" }, { content: "y", editorAgentId: "e", version: 4, at: "t" }],
          }),
        ]}
      />,
    );
    expect(screen.getByTestId("artifact-history-trimmed")).toHaveTextContent("Versions before v4 are no longer kept.");
  });
});

const decision = (over: Partial<DecisionRecord>): DecisionRecord => ({
  type: "VOTE",
  outcome: null,
  winner: null,
  tally: null,
  dissents: [],
  method: "vote",
  decidedAtPhase: null,
  ...over,
});

describe("decisionSummary", () => {
  it("names a vote's winner, and says when none won", () => {
    expect(decisionSummary(decision({ winner: "Ship it" }))[0]!.slice(0, 1)).toEqual(["groups.decisionPlain.voteWon"]);
    expect(decisionSummary(decision({ winner: "Ship it" }))[0]![2]).toEqual({ winner: "Ship it" });
    expect(decisionSummary(decision({ winner: null }))[0]![0]).toBe("groups.decisionPlain.voteNoWinner");
  });

  it("tells a verdict, a tie and an arbitration apart", () => {
    expect(decisionSummary(decision({ type: "VERDICT", winner: "PRO", method: "debate-judgment" }))[0]![0]).toBe(
      "groups.decisionPlain.verdictWinner",
    );
    expect(decisionSummary(decision({ type: "VERDICT", winner: null, method: "debate-judgment" }))[0]![0]).toBe(
      "groups.decisionPlain.verdictTie",
    );
    expect(decisionSummary(decision({ type: "VERDICT", winner: null, method: "arbitration" }))[0]![0]).toBe(
      "groups.decisionPlain.arbitration",
    );
  });

  it("never names an agreement's winner, which is a proposal id", () => {
    const sentences = decisionSummary(decision({ type: "AGREEMENT", winner: "p3", method: "negotiation" }));
    expect(sentences).toHaveLength(1);
    expect(sentences[0]![0]).toBe("groups.decisionPlain.agreement");
    expect(JSON.stringify(sentences)).not.toContain("p3");
  });

  it("counts the members who disagreed", () => {
    const sentences = decisionSummary(
      decision({ winner: "A", dissents: [{ agentId: "x", displayName: "X", position: "no" }] }),
    );
    expect(sentences[1]).toEqual(["groups.decisionPlain.dissents", "Members who disagreed: {{n}}.", { n: 1 }]);
    expect(decisionSummary(decision({ type: "NONE" }))).toHaveLength(1);
  });

  it("is the first line of the decision card", () => {
    wrap(<DecisionRecordCard decision={decision({ winner: "Ship it", dissents: [{ agentId: "x", displayName: "X", position: "no" }, { agentId: "y", displayName: "Y", position: "no" }] })} />);
    expect(screen.getByTestId("decision-plain")).toHaveTextContent("The team voted, and “Ship it” won. Members who disagreed: 2.");
  });
});

describe("HumanTurnBanner", () => {
  const base = {
    displayName: "Gregor",
    renderedPrompt: "Your opinion on the launch?",
    onSubmit: vi.fn(),
  };

  it("speaks to the member in the second person by default, and takes their answer", () => {
    const onSubmit = vi.fn();
    wrap(<HumanTurnBanner {...base} onSubmit={onSubmit} />);
    const region = screen.getByRole("region", { name: "It's your turn, Gregor" });
    expect(region).toBeInTheDocument();
    expect(screen.queryByTestId("human-turn-who-may-answer")).toBeNull();
    fireEvent.change(screen.getByTestId("human-turn-input"), { target: { value: "  Go  " } });
    fireEvent.click(screen.getByTestId("human-turn-submit"));
    expect(onSubmit).toHaveBeenCalledWith("Go");
  });

  it("tells someone else whose turn it is and who may answer it", () => {
    wrap(<HumanTurnBanner {...base} viewerIsMember={false} />);
    expect(screen.getByRole("region", { name: "Gregor's turn" })).toBeInTheDocument();
    expect(screen.getByTestId("human-turn-who-may-answer")).toHaveTextContent(
      "The discussion waits for Gregor. Only Gregor or an administrator can answer this turn.",
    );
    // The server decides; the form stays for an administrator.
    expect(screen.getByTestId("human-turn-input")).toBeInTheDocument();
  });

  it("keeps the second person when the viewer is the member", () => {
    wrap(<HumanTurnBanner {...base} viewerIsMember />);
    expect(screen.getByRole("region", { name: "It's your turn, Gregor" })).toBeInTheDocument();
  });
});
