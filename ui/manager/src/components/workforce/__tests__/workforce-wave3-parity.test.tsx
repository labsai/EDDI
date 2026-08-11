import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * The Manager and the Workforce render group discussions through three
 * INDEPENDENT transcript components. That has already cost this codebase once:
 * a DISSENT badge added to the Manager's renderer left the other two showing a
 * minority report as an ordinary opinion.
 *
 * These are source-level assertions rather than render tests on purpose — they
 * pin the wiring itself, which is the thing that drifts. A render test on one
 * surface cannot fail when a NEW surface forgets to mount the shared panel.
 */
const SRC = resolve(__dirname, "../../..");
const read = (p: string) => readFileSync(resolve(SRC, p), "utf-8");

describe("wave-3 parity across group-discussion surfaces", () => {
  const surfaces: [label: string, file: string][] = [
    ["Manager transcript", "components/groups/discussion-transcript.tsx"],
    ["Workforce board", "pages/workforce/workforce-board.tsx"],
    ["Workforce history viewer", "components/workforce/conversation-viewer.tsx"],
  ];

  for (const [label, file] of surfaces) {
    it(`${label} mounts the shared DiscussionInsights panel`, () => {
      const source = read(file);
      expect(source).toContain("DiscussionInsights");
      expect(source).toMatch(/<DiscussionInsights\b/);
    });
  }

  // The wave-3 phase/entry types must have an icon on every surface that keys a
  // phase marker off them, or a vote/negotiation round renders as a bare pin.
  const iconMaps: [label: string, file: string][] = [
    ["Manager phase header", "components/groups/phase-header.tsx"],
    ["Workforce board transcript", "components/workforce/board-transcript.tsx"],
    ["Workforce history viewer", "components/workforce/conversation-viewer.tsx"],
  ];

  for (const [label, file] of iconMaps) {
    it(`${label} has icons for the wave-3 phase types`, () => {
      const source = read(file);
      for (const type of ["VOTE", "PROPOSAL", "BARGAIN", "RETRO"]) {
        expect(source, `${label} is missing a ${type} icon`).toMatch(
          new RegExp(`\\b${type}:\\s*"`),
        );
      }
    });
  }

  // AWAITING_HUMAN_INPUT must be handled wherever group state is mapped, or the
  // state renders as an unknown/blank badge.
  const stateMaps: [label: string, file: string][] = [
    ["Manager group detail", "pages/group-detail.tsx"],
    ["Manager transcript", "components/groups/discussion-transcript.tsx"],
    ["Workforce history viewer", "components/workforce/conversation-viewer.tsx"],
    ["Workforce session history", "components/workforce/session-history.tsx"],
    ["Workforce history page", "pages/workforce/workforce-history.tsx"],
  ];

  for (const [label, file] of stateMaps) {
    it(`${label} handles the AWAITING_HUMAN_INPUT state`, () => {
      expect(read(file)).toContain("AWAITING_HUMAN_INPUT");
    });
  }

  // The structured OUTCOMES of the newer collaboration modes (task board,
  // decision record) must render on every transcript surface, not only the
  // Manager's — a debate whose verdict shows on one page out of three is a
  // feature that "works" only where the demo happened to run.
  const outcomeSurfaces: [label: string, file: string][] = [
    ["Manager transcript", "components/groups/discussion-transcript.tsx"],
    ["Workforce board", "pages/workforce/workforce-board.tsx"],
    ["Workforce history viewer", "components/workforce/conversation-viewer.tsx"],
  ];

  for (const [label, file] of outcomeSurfaces) {
    it(`${label} mounts the persisted task board`, () => {
      expect(read(file)).toMatch(/<PersistedTaskBoard[\s>]/);
    });
  }

  it("the board transcript and history viewer render the decision record", () => {
    expect(read("components/workforce/board-transcript.tsx")).toMatch(/<DecisionRecordCard[\s>]/);
    expect(read("components/workforce/conversation-viewer.tsx")).toMatch(/<DecisionRecordCard[\s>]/);
    expect(read("components/groups/discussion-transcript.tsx")).toMatch(/<DecisionRecordCard[\s>]/);
  });

  it("the Workforce board passes live convergence state to its transcript", () => {
    const board = read("pages/workforce/workforce-board.tsx");
    expect(board).toMatch(/convergence=\{/);
    const transcript = read("components/workforce/board-transcript.tsx");
    expect(transcript).toContain("ConvergenceProgress");
  });

  // Editors that can produce a ROLE:-addressed phase with no member carrying
  // the role must warn — on every surface that edits members or styles.
  const roleWarningSurfaces: [label: string, file: string][] = [
    ["Manager group wizard", "pages/group-wizard.tsx"],
    ["Manager create dialog", "components/groups/create-group-dialog.tsx"],
    ["Workforce settings", "pages/workforce/workforce-settings.tsx"],
    ["Group config panel", "components/groups/group-config-panel.tsx"],
  ];

  for (const [label, file] of roleWarningSurfaces) {
    it(`${label} checks role coverage`, () => {
      expect(read(file)).toContain("uncoveredRolePhases");
    });
  }

  it("every style picker offers what the backend supports, not a hardcoded list", () => {
    for (const file of [
      "pages/group-wizard.tsx",
      "components/groups/create-group-dialog.tsx",
      "pages/workforce/workforce-settings.tsx",
    ]) {
      expect(read(file)).toContain("useAvailableStyles");
    }
  });

  it("NEGOTIATION is reachable from the template-driven Workforce wizard", () => {
    const templates = read("lib/group-templates.ts");
    expect(templates).toContain('"NEGOTIATION"');
  });

  it("the Workforce board can answer a human turn without leaving the surface", () => {
    const source = read("pages/workforce/workforce-board.tsx");
    // Submitting your own turn is participation, not governance — unlike the
    // approval pause, it must be answerable on the board itself.
    expect(source).toContain("HumanTurnBanner");
    expect(source).toContain("useSubmitHumanInput");
  });
});
