import { afterEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { MeasureCard } from "../measure-card";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { HEALTH_AGENTS, SESSION_ROWS } from "@/test/mocks/health-fixtures";
import { resetHealthMockState } from "@/test/mocks/health-handlers";
import { AgentHealthPanel } from "../agent-health-panel";
import { CompareDialog } from "../compare-dialog";
import { SessionsDialog } from "../sessions-dialog";
import { drillForResolution } from "../drill";

/**
 * What the real backend (planning/agent-health-api.md sections 12 and 13) does where the UI's
 * mocks first assumed otherwise: withheld rates, hints on valued measures, untestable changes,
 * rows without an outcome, the two kinds of 403 and the EDIT requirement of the drill-down.
 */

afterEach(() => resetHealthMockState());

const renderPanel = (agentId: string, canEdit = true) =>
  renderWithProviders(<AgentHealthPanel agentId={agentId} latestVersion={8} canEdit={canEdit} defaultOpen />);

const measureCard = async (id: string) => {
  const cards = await screen.findAllByTestId("health-measure");
  const card = cards.find((c) => c.getAttribute("data-measure-id") === id);
  if (!card) throw new Error(`no card for ${id}`);
  return card;
};

describe("outcome-coverage gate", () => {
  it("shows a withheld rate as a dash with its hint, and keeps the session counts of the split", async () => {
    renderPanel(HEALTH_AGENTS.lowCoverage);
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("health-value")).toHaveTextContent("—");
    expect(within(resolution).getByTestId("health-hint")).toHaveTextContent("32 %");
    // No shares can be drawn, and a null part is not 0 %.
    expect(within(resolution).queryByTestId("resolution-split")).toBeNull();
    expect(within(resolution).getByTestId("resolution-split-withheld")).toBeInTheDocument();
    expect(within(resolution).getByTestId("resolution-n-confirmed")).toHaveTextContent("546");
    expect(within(resolution).getByTestId("resolution-n-silence")).toHaveTextContent("291");
    // The silence warning is about shares; there are none.
    expect(within(resolution).queryByTestId("resolution-silence-warning")).toBeNull();
    // The other rates are withheld the same way, and coverage itself stays visible.
    expect(within(await measureCard("escalationRate")).getByTestId("health-value")).toHaveTextContent("—");
    expect(within(await measureCard("outcomeCoverage")).getByTestId("health-value")).toHaveTextContent("32");
    expect(screen.getByTestId("health-coverage")).toBeInTheDocument();
  });

  it("keeps the figure and shows the hint while outcomes are still being evaluated", async () => {
    renderPanel(HEALTH_AGENTS.evaluating);
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("health-value")).toHaveTextContent("71");
    expect(within(resolution).getByTestId("health-hint")).toHaveTextContent("2026-11-02");
    // A split with a value is still drawn.
    expect(within(resolution).getByTestId("resolution-split")).toBeInTheDocument();
  });
});

describe("changes that cannot be tested for significance", () => {
  it("does not call them within normal variation", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const p95 = await measureCard("turnDurationP95");
    expect(within(p95).getByTestId("health-delta")).toHaveAttribute("data-significance", "untested");
    const failures = await measureCard("agentFailureRate");
    // Testable and not significant is the other reading.
    expect(within(failures).getByTestId("health-delta")).toHaveAttribute("data-significance", "variation");
    // Not significant can also mean too few samples in either period: the text must not claim ordinary variation.
    expect(within(failures).getByTestId("health-delta")).toHaveTextContent("not significant, or too little data");
    expect(within(failures).getByTestId("health-delta")).not.toHaveTextContent("normal variation");
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("health-delta")).toHaveAttribute("data-significance", "significant");
  });
});

describe("labels the backend parameterises", () => {
  it("keeps the Apdex threshold T in the translated label", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const apdex = await measureCard("apdex");
    expect(within(apdex).getByTestId("health-measure-label")).toHaveTextContent("T = 4 s");
  });
});

describe("series without values", () => {
  it("says there is no trend for a measure whose daily points are all null", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const rating = await measureCard("ratingScore");
    await waitFor(() => expect(within(rating).getByTestId("health-sparkline-empty")).toBeInTheDocument());
    expect(within(rating).queryByTestId("health-sparkline")).toBeNull();
  });
});

describe("errors", () => {
  it("shows the backend's own sentence for an unknown agent", async () => {
    renderPanel(HEALTH_AGENTS.missing);
    expect(await screen.findByTestId("health-error")).toHaveTextContent("Agent not found.");
  });

  it("reads the VIEW refusal of the whole panel from a body without a reason", async () => {
    renderPanel(HEALTH_AGENTS.denied);
    expect(await screen.findByTestId("health-forbidden")).toBeInTheDocument();
  });
});

describe("the session drill-down needs EDIT", () => {
  it("explains why without asking the backend when the caller cannot edit", async () => {
    const requested: string[] = [];
    const record = ({ request }: { request: Request }) => void requested.push(new URL(request.url).pathname);
    server.events.on("request:start", record);
    renderPanel(HEALTH_AGENTS.healthy, false);
    const resolution = await measureCard("resolutionRate");
    await userEvent.click(within(resolution).getByTestId("health-drill-resolutionRate"));
    expect(await screen.findByTestId("health-denied-FORBIDDEN")).toBeInTheDocument();
    expect(screen.queryByTestId("health-denied-REVIEW_DISABLED")).toBeNull();
    expect(screen.queryByTestId("health-sessions-table")).toBeNull();
    expect(requested.some((p) => p.endsWith("/health/sessions"))).toBe(false);
    server.events.removeListener("request:start", record);
  });

  it("tells a version with review off from a caller without EDIT, using the reason of the 403 body", async () => {
    renderPanel(HEALTH_AGENTS.noReview);
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("health-drill-resolutionRate"));
    expect(await screen.findByTestId("health-denied-REVIEW_DISABLED")).toBeInTheDocument();
  });
});

describe("sessions rows", () => {
  const filter = drillForResolution("silence");
  const open = (query: Parameters<typeof SessionsDialog>[0]["query"], f = filter) =>
    renderWithProviders(<SessionsDialog open onClose={() => {}} agentId="a1" query={query} filter={f} />);

  it("shows a dash for the outcome and evidence of an origin that is not judged", async () => {
    open({ period: "30d", origins: ["schedule"] }, { title: ["health.sessions.title", "Sessions"] });
    const rows = await screen.findAllByTestId("health-session-row");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveAttribute("data-outcome", "");
    expect(within(rows[0]!).getByTestId("health-session-not-judged")).toHaveTextContent("—");
  });

  it("marks a provisional row, and keys rows by the step index a session starts at", async () => {
    const all = SESSION_ROWS.filter((r) => r.outcome !== null);
    // Session numbers are step indexes, not 0, 1, 2: the later sessions of a conversation start at their first turn.
    expect(all.map((r) => r.sessionNo)).toContain(17);
    open({ period: "30d" }, { title: ["health.sessions.title", "Sessions"], outcome: ["RESOLVED"] });
    const rows = await screen.findAllByTestId("health-session-row");
    expect(rows).toHaveLength(1);
    expect(within(rows[0]!).getByTestId("health-session-provisional")).toBeInTheDocument();
  });

  it("says which versions are listed unless one is chosen", async () => {
    const view = open({ period: "30d" });
    expect(await screen.findByTestId("health-sessions-scope")).toBeInTheDocument();
    view.unmount();
    open({ period: "30d", version: 8 });
    await screen.findAllByTestId("health-session-row");
    expect(screen.queryByTestId("health-sessions-scope")).toBeNull();
  });
});

describe("resolution split without sessions", () => {
  it("falls back to the missing state and the measure's own hint when every part has n = 0", async () => {
    const zero = ["confirmed", "selfReported", "agentClosed", "silence"].map((key) => ({ key, label: key, value: null, n: 0 }));
    renderWithProviders(
      <MeasureCard
        measure={{ id: "resolutionRate", label: "Resolution", kind: "SCORED", unit: "ratio", value: null, n: 0, band: "GREY", significant: false, breakdown: zero, hint: "Outcomes are not available yet" }}
      />,
    );
    expect(screen.getByTestId("resolution-split-missing")).toBeInTheDocument();
    expect(screen.queryByTestId("resolution-split-withheld")).toBeNull();
    expect(screen.getByTestId("health-hint")).toHaveTextContent("not available");
  });
});

describe("outcome suggestions", () => {
  it("shows a dash for an action that matched no heuristic instead of failing", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await userEvent.click(await screen.findByTestId("health-map-outcomes"));
    const list = await screen.findByTestId("health-suggestions");
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    expect(within(list).getByTestId("health-suggestion-none")).toBeInTheDocument();
  });
});

describe("version compare", () => {
  it("says so when a version had no turns in its window", async () => {
    server.use(
      http.get("*/agents/:agentId/health/compare", () => {
        const empty = { reliability: { restricted: false, measures: [{ id: "apdex", label: "Apdex", kind: "SCORED", unit: "score", value: null, n: 0, band: "GREY", significant: false }] } };
        return HttpResponse.json({
          agentId: "a1",
          from: { version: 6, window: { from: "2026-10-01", to: "2026-10-14" }, sections: empty },
          to: { version: 8, window: { from: "2026-10-15", to: "2026-11-10" }, sections: empty },
          deltas: [{ measureId: "apdex", from: null, to: null, delta: null, significant: false, better: null }],
        });
      }),
    );
    renderWithProviders(<CompareDialog open onClose={() => {}} agentId="a1" latestVersion={8} />);
    expect(await screen.findAllByTestId("health-compare-no-turns")).toHaveLength(2);
    expect(screen.getByTestId("health-compare-table")).toBeInTheDocument();
  });

  it("does not claim a side has no turns when its only sections are restricted, or when another section has values", async () => {
    server.use(
      http.get("*/agents/:agentId/health/compare", () => {
        const restricted = { quality: { restricted: true, measures: [] } };
        const valued = {
          quality: { restricted: true, measures: [] },
          reliability: { restricted: false, measures: [{ id: "apdex", label: "Apdex", kind: "SCORED", unit: "score", value: 0.9, n: 9, band: "GREEN", significant: false }] },
        };
        return HttpResponse.json({
          agentId: "a1",
          from: { version: 6, window: { from: "2026-10-01", to: "2026-10-14" }, sections: restricted },
          to: { version: 8, window: { from: "2026-10-15", to: "2026-11-10" }, sections: valued },
          deltas: [],
        });
      }),
    );
    renderWithProviders(<CompareDialog open onClose={() => {}} agentId="a1" latestVersion={8} />);
    await screen.findByTestId("health-compare-table");
    expect(screen.queryByTestId("health-compare-no-turns")).toBeNull();
  });
});

describe("compare version pickers", () => {
  it("keeps the derived older version selectable when the newer one sits at the bottom of the capped list", async () => {
    renderWithProviders(<CompareDialog open onClose={() => {}} agentId="a1" latestVersion={30} />);
    const to = screen.getByTestId("health-compare-to") as HTMLSelectElement;
    const from = screen.getByTestId("health-compare-from") as HTMLSelectElement;
    // The newer list is capped at 25 versions: v30 … v6.
    await userEvent.selectOptions(to, "6");
    expect(from.value).toBe("5");
    const older = Array.from(from.options).map((o) => o.value);
    expect(older[0]).toBe("5");
    expect(older).toContain("1");
    expect(older).not.toContain("6");
  });
});
