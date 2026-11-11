import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Link } from "react-router-dom";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { HEALTH_AGENTS } from "@/test/mocks/health-fixtures";
import { resetHealthMockState } from "@/test/mocks/health-handlers";
import { AgentHealthPanel } from "../agent-health-panel";
import { formatShortDate } from "../format";
import { api } from "../deps";

// The profile, report links and export are offered only while the mock API answers them.
const mockFlag = window as unknown as Record<string, unknown>;
beforeEach(() => {
  mockFlag.__EDDI_MOCK_ACTIVE__ = true;
});
afterEach(() => {
  resetHealthMockState();
  delete mockFlag.__EDDI_MOCK_ACTIVE__;
});

function panel(agentId: string, props: { canEdit?: boolean; latestVersion?: number } = {}) {
  return (
    <AgentHealthPanel agentId={agentId} latestVersion={props.latestVersion ?? 8} canEdit={props.canEdit ?? true} defaultOpen />
  );
}
const renderPanel = (agentId: string, props: { canEdit?: boolean; latestVersion?: number } = {}) =>
  renderWithProviders(panel(agentId, props));

const measureCard = async (id: string) => {
  const cards = await screen.findAllByTestId("health-measure");
  const card = cards.find((c) => c.getAttribute("data-measure-id") === id);
  if (!card) throw new Error(`no card for ${id}`);
  return card;
};

describe("AgentHealthPanel", () => {
  it("fetches nothing while collapsed, and only starts when opened", async () => {
    const urls: string[] = [];
    const listener = ({ request }: { request: Request }) => {
      if (new URL(request.url).pathname.includes("/health")) urls.push(request.url);
    };
    server.events.on("request:start", listener);
    try {
      renderWithProviders(<AgentHealthPanel agentId={HEALTH_AGENTS.healthy} latestVersion={8} canEdit />);
      expect(screen.queryByTestId("health-filters")).toBeNull();
      // Give a wrongly-enabled query every chance to fire.
      await new Promise((r) => setTimeout(r, 150));
      expect(urls).toEqual([]);

      await userEvent.click(screen.getByTestId("agent-health-toggle"));
      expect(await screen.findByTestId("health-section-reliability")).toBeInTheDocument();
      expect(urls.some((u) => /\/agents\/[^/]+\/health(\?|$)/.test(u))).toBe(true);
    } finally {
      server.events.removeListener("request:start", listener);
    }
  });

  it("puts the toggle inside the heading and wires aria-controls to the body", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const toggle = screen.getByTestId("agent-health-toggle");
    expect(toggle.closest("h2")).not.toBeNull();
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    const bodyId = toggle.getAttribute("aria-controls")!;
    expect(document.getElementById(bodyId)).not.toBeNull();
  });

  it("shows the status of a healthy agent once the backend sends one (H4)", async () => {
    renderPanel(HEALTH_AGENTS.healthyH4);
    expect(await screen.findByTestId("health-status-ring")).toBeInTheDocument();
    expect(screen.getByTestId("health-status-label")).toBeInTheDocument();
  });

  it("shows a healthy agent: six sections, values in their units and the golden check", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    // H3: no status yet.
    expect(await screen.findByTestId("health-section-reliability")).toBeInTheDocument();
    expect(screen.queryByTestId("health-status-ring")).toBeNull();
    for (const id of ["reliability", "effectiveness", "quality", "efficiency", "adoption", "business"]) {
      expect(await screen.findByTestId(`health-section-${id}`)).toBeInTheDocument();
    }
    const failure = await measureCard("agentFailureRate");
    expect(within(failure).getByTestId("health-value")).toHaveTextContent("0.4%");
    expect(within(failure).getByTestId("health-interval")).toHaveTextContent("0.2%–0.7%");
    expect(within(await measureCard("turnDurationP95")).getByTestId("health-value")).toHaveTextContent("3.20s");
    expect(within(await measureCard("costPerSession")).getByTestId("health-value")).toHaveTextContent("$0.03");
    expect(screen.getByTestId("health-check")).toHaveAttribute("data-state", "PASS");
    expect(screen.getByTestId("health-data-through")).toBeInTheDocument();
  });

  it("explains an agent that needs attention with its reasons and signed deltas", async () => {
    renderPanel(HEALTH_AGENTS.attention);
    const status = await screen.findByTestId("health-status");
    expect(within(status).getByTestId("health-reasons").querySelectorAll("li")).toHaveLength(2);
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("health-band")).toHaveAttribute("data-band", "RED");
    // The interval follows the (overridden) value: it must contain it.
    expect(within(resolution).getByTestId("health-value")).toHaveTextContent("41%");
    expect(within(resolution).getByTestId("health-interval")).toHaveTextContent("38%–44%");
    const delta = within(resolution).getByTestId("health-delta");
    expect(delta).toHaveTextContent("−12");
    expect(delta).toHaveAttribute("data-tone", "worse");
    expect(screen.getByTestId("health-check")).toHaveAttribute("data-state", "FAIL");
    expect(screen.getByTestId("health-check-stale")).toBeInTheDocument();
  });

  it("shows the resolution split as four parts, each a share of the resolved sessions", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    // 0.30 / 0.20 / 0.05 / 0.16 of a 0.71 rate.
    expect(within(resolution).getByTestId("resolution-confirmed")).toHaveTextContent("42%");
    expect(within(resolution).getByTestId("resolution-selfReported")).toHaveTextContent("7.0%");
    expect(within(resolution).getByTestId("resolution-agentClosed")).toHaveTextContent("28%");
    expect(within(resolution).getByTestId("resolution-silence")).toHaveTextContent("23%");
    expect(within(resolution).queryByTestId("resolution-silence-warning")).toBeNull();
  });

  it("reveals what each part means on request, reachable by keyboard", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).queryByTestId("resolution-hints")).toBeNull();
    const toggle = within(resolution).getByRole("button", { expanded: false });
    toggle.focus();
    await userEvent.keyboard("{Enter}");
    expect(within(resolution).getByTestId("resolution-hints").querySelectorAll("dt")).toHaveLength(4);
  });

  it("warns when most of the resolutions are assumed from silence", async () => {
    renderPanel(HEALTH_AGENTS.silence);
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("resolution-silence-warning")).toBeInTheDocument();
  });

  it("renders a not-enough-data agent with grey measures and their hints, no values", async () => {
    renderPanel(HEALTH_AGENTS.noData);
    expect(await screen.findByTestId("health-status-label")).toBeInTheDocument();
    const failure = await measureCard("agentFailureRate");
    expect(within(failure).getByTestId("health-band")).toHaveAttribute("data-band", "GREY");
    expect(within(failure).getByTestId("health-value")).toHaveTextContent("—");
    expect(within(failure).getByTestId("health-value")).toHaveClass("text-muted-foreground");
    expect(within(failure).getByTestId("health-hint")).toHaveTextContent("Fewer than 30 engaged sessions");
    expect(within(failure).queryByTestId("health-thresholds")).toBeNull();
  });

  it("does not mute an informational figure that has data", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const sessions = await measureCard("sessions");
    expect(within(sessions).getByTestId("health-value")).toHaveTextContent("1,820");
    expect(within(sessions).getByTestId("health-value")).not.toHaveClass("text-muted-foreground");
  });

  it("shows an access message for a restricted section instead of measures", async () => {
    renderPanel(HEALTH_AGENTS.restricted);
    const quality = await screen.findByTestId("health-section-quality");
    expect(within(quality).getByTestId("health-restricted")).toBeInTheDocument();
    expect(within(quality).queryByTestId("health-measure")).toBeNull();
    expect(within(await screen.findByTestId("health-section-reliability")).getAllByTestId("health-measure").length).toBeGreaterThan(0);
  });

  it("hides the status ring while the backend sends no status", async () => {
    renderPanel(HEALTH_AGENTS.preH4);
    await screen.findByTestId("health-section-reliability");
    expect(screen.queryByTestId("health-status")).toBeNull();
    expect(screen.queryByTestId("health-status-ring")).toBeNull();
  });

  it("draws a sparkline per measure and lists the changes in words", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const failure = await measureCard("agentFailureRate");
    await waitFor(() => expect(within(failure).getByTestId("health-sparkline")).toBeInTheDocument());
    expect(within(failure).getAllByTestId("health-marker").length).toBeGreaterThan(0);
    const list = await within(await screen.findByTestId("health-section-reliability")).findByTestId("health-marker-list");
    expect(list.querySelectorAll("li").length).toBeGreaterThanOrEqual(3);
  });

  it("describes a sparkline by its range, not as a movement from one value to the other", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const failure = await measureCard("agentFailureRate");
    const spark = await within(failure).findByTestId("health-sparkline");
    expect(spark.getAttribute("aria-label")).toMatch(/between .+ and .+/);
    expect(spark.getAttribute("aria-label")).not.toMatch(/from .+ to /);
  });

  it("dates a marker in the list by the UTC day the sparkline places it on", async () => {
    // Late on 3 Nov in UTC, already 4 Nov east of it and earlier in the day west of it.
    server.use(
      http.get("*/agents/:agentId/health/series", ({ request }) => {
        const measures = new URL(request.url).searchParams.getAll("measure");
        return HttpResponse.json({
          agentId: "x",
          period: { from: "2026-10-12", to: "2026-11-10", days: 30 },
          series: measures.map((m) => ({ measure: m, group: null, unit: "ratio", points: [] })),
          markers: [{ id: "late", at: "2026-11-03T23:30:00Z", kind: "NOTE", label: "Late note" }],
        });
      }),
    );
    const before = process.env.TZ;
    process.env.TZ = "Asia/Tokyo";
    try {
      renderPanel(HEALTH_AGENTS.healthy);
      const list = await within(await screen.findByTestId("health-section-reliability")).findByTestId("health-marker-list");
      expect(list).toHaveTextContent(formatShortDate("2026-11-03"));
      expect(list).not.toHaveTextContent(formatShortDate("2026-11-04"));
    } finally {
      if (before === undefined) delete process.env.TZ;
      else process.env.TZ = before;
    }
  });

  it("says so, with a retry, when the trends of a section fail to load", async () => {
    let fail = true;
    server.use(
      http.get("*/agents/:agentId/health/series", () =>
        fail ? HttpResponse.json({ error: "internal", message: "Boom" }, { status: 500 }) : undefined,
      ),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    const section = await screen.findByTestId("health-section-reliability");
    expect(await within(section).findByTestId("health-series-error")).toBeInTheDocument();
    // The figures still render; only the trends are missing.
    expect(within(await measureCard("agentFailureRate")).getByTestId("health-value")).toHaveTextContent("0.4%");
    fail = false;
    await userEvent.click(within(section).getByTestId("health-series-retry"));
    await waitFor(() => expect(within(section).queryByTestId("health-series-error")).toBeNull());
    expect(await within(section).findAllByTestId("health-sparkline")).not.toHaveLength(0);
  });

  it("marks a critical measure with an image role, not just a tooltip", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const failure = await measureCard("agentFailureRate");
    const star = failure.querySelector("h4 svg");
    expect(star).toHaveAttribute("role", "img");
    expect(star?.getAttribute("aria-label")).toBeTruthy();
  });

  it("renders unknown measures, sections and marker kinds from their own fields", async () => {
    renderPanel(HEALTH_AGENTS.unknown);
    const mystery = await measureCard("mystery:metric");
    expect(within(mystery).getByTestId("health-value")).toHaveTextContent("42");
    expect(within(mystery).getByRole("heading", { level: 4 })).toHaveTextContent("Mystery metric");
    expect(await screen.findByTestId("health-section-carbon")).toBeInTheDocument();
    expect(within(screen.getByTestId("health-section-carbon")).getByTestId("health-measure")).toHaveAttribute("data-measure-id", "gramsCo2");
    // A section the backend sent as null is skipped.
    expect(screen.queryByTestId("health-section-broken")).toBeNull();
    // A marker of a kind the UI has never seen is listed by its label.
    const list = await within(screen.getByTestId("health-section-reliability")).findByTestId("health-marker-list");
    expect(list).toHaveTextContent("Quota raised");
  });

  it("shows no change against a measure that has no value", async () => {
    renderPanel(HEALTH_AGENTS.unknown);
    const apdex = await measureCard("apdex");
    expect(within(apdex).getByTestId("health-value")).toHaveTextContent("—");
    expect(within(apdex).queryByTestId("health-delta")).toBeNull();
    expect(within(apdex).getByTestId("health-hint")).toBeInTheDocument();
  });

  it("offers the outcome mapping from the coverage measure and links to Agent Studio", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const coverage = await measureCard("outcomeCoverage");
    await userEvent.click(within(coverage).getByTestId("health-map-outcomes"));
    const dialog = await screen.findByTestId("health-suggestions-dialog");
    expect(await within(dialog).findByText("order_confirmed")).toBeInTheDocument();
    expect(within(dialog).getByTestId("health-suggestions").querySelectorAll("li")).toHaveLength(3);
    expect(within(dialog).getByTestId("health-suggestions-config-link")).toHaveAttribute("href", `/manage/studio/${HEALTH_AGENTS.healthy}`);
  });

  it("hides the profile and report links from someone who cannot edit", async () => {
    renderPanel(HEALTH_AGENTS.healthy, { canEdit: false });
    await screen.findByTestId("health-section-reliability");
    expect(screen.queryByTestId("health-open-profile")).toBeNull();
    expect(screen.queryByTestId("health-open-reports")).toBeNull();
    expect(screen.getByTestId("health-open-compare")).toBeInTheDocument();
    expect(screen.getByTestId("health-export-json")).toBeInTheDocument();
  });

  it("offers no profile, report links or export against a backend that has none of them yet", async () => {
    delete mockFlag.__EDDI_MOCK_ACTIVE__;
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    expect(screen.getByTestId("health-open-compare")).toBeInTheDocument();
    expect(screen.queryByTestId("health-open-profile")).toBeNull();
    expect(screen.queryByTestId("health-open-reports")).toBeNull();
    expect(screen.queryByTestId("health-export-json")).toBeNull();
    expect(screen.queryByTestId("health-export-csv")).toBeNull();
  });

  it("opens and scrolls to the collapsed panel when a link adds #health", async () => {
    const scroll = vi.fn();
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scroll;
    try {
      renderWithProviders(
        <>
          <Link to="#health" data-testid="to-health">health</Link>
          <AgentHealthPanel agentId={HEALTH_AGENTS.healthy} latestVersion={8} canEdit />
        </>,
      );
      expect(screen.getByTestId("agent-health-toggle")).toHaveAttribute("aria-expanded", "false");
      await userEvent.click(screen.getByTestId("to-health"));
      expect(screen.getByTestId("agent-health-toggle")).toHaveAttribute("aria-expanded", "true");
      expect(await screen.findByTestId("health-section-reliability")).toBeInTheDocument();
      expect(scroll).toHaveBeenCalled();
    } finally {
      Element.prototype.scrollIntoView = original;
    }
  });

  it("explains a whole-panel 403 and disables compare and export", async () => {
    renderPanel(HEALTH_AGENTS.denied);
    expect(await screen.findByTestId("health-forbidden")).toBeInTheDocument();
    expect(screen.queryByTestId("health-section-reliability")).toBeNull();
    expect(screen.getByTestId("health-open-compare")).toBeDisabled();
    expect(screen.getByTestId("health-export-json")).toBeDisabled();
    expect(screen.getByTestId("health-export-csv")).toBeDisabled();
  });

  it("changes the period from the filter bar", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    expect(screen.getByTestId("health-period-30d")).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByTestId("health-period-7d"));
    expect(screen.getByTestId("health-period-7d")).toHaveAttribute("aria-pressed", "true");
  });

  it("closes the origin list on an outside click and on Escape", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    const details = screen.getByTestId("health-origin-filter").closest("details")!;
    await userEvent.click(screen.getByTestId("health-origin-filter"));
    expect(details.open).toBe(true);
    fireEvent.mouseDown(document.body);
    expect(details.open).toBe(false);
    await userEvent.click(screen.getByTestId("health-origin-filter"));
    expect(details.open).toBe(true);
    await userEvent.keyboard("{Escape}");
    expect(details.open).toBe(false);
  });
});

describe("version compare", () => {
  it("lists deltas with a verdict and keeps an insignificant change neutral", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-compare"));
    const rows = await screen.findAllByTestId("health-compare-row");
    const verdictOf = (id: number) => rows[id]!.getAttribute("data-verdict");
    expect(verdictOf(0)).toBe("worse");
    expect(verdictOf(2)).toBe("better");
    expect(verdictOf(3)).toBe("neutral");
    expect(verdictOf(4)).toBe("neutral");
  });

  it("derives its defaults from the latest version when it arrives late, and keeps older below newer", async () => {
    const view = renderPanel(HEALTH_AGENTS.healthy, { latestVersion: 1 });
    await screen.findByTestId("health-section-reliability");
    expect(screen.getByTestId("health-open-compare")).toBeDisabled();
    view.rerender(panel(HEALTH_AGENTS.healthy, { latestVersion: 8 }));
    await userEvent.click(screen.getByTestId("health-open-compare"));
    expect(await screen.findByTestId("health-compare-from")).toHaveValue("7");
    expect(screen.getByTestId("health-compare-to")).toHaveValue("8");
    // Older offers only versions below the newer one.
    const olderOptions = within(screen.getByTestId("health-compare-from")).getAllByRole("option").map((o) => Number((o as HTMLOptionElement).value));
    expect(Math.max(...olderOptions)).toBeLessThan(8);
    // Moving the newer one below the chosen older falls back to a valid pair.
    await userEvent.selectOptions(screen.getByTestId("health-compare-to"), "3");
    expect(screen.getByTestId("health-compare-from")).toHaveValue("2");
  });

  it("passes the older version's window to the backend", async () => {
    const seen: string[] = [];
    server.use(
      http.get("*/agents/:agentId/health/compare", ({ request }) => {
        seen.push(request.url);
        return HttpResponse.json({ agentId: "a", from: { version: 7, window: { from: "a", to: "b" }, sections: {} }, to: { version: 8, window: { from: "a", to: "b" }, sections: {} }, deltas: [] });
      }),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-compare"));
    await waitFor(() => expect(seen.some((u) => u.includes("days=14"))).toBe(true));
    await userEvent.selectOptions(screen.getByTestId("health-compare-days"), "30");
    await waitFor(() => expect(seen.some((u) => u.includes("days=30"))).toBe(true));
  });
});

describe("session drill-down", () => {
  it("lists the sessions behind a measure and links each to the conversation view", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    await userEvent.click(within(resolution).getByTestId("health-drill-resolutionRate"));
    const rows = await screen.findAllByTestId("health-session-row");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]!).getByRole("link")).toHaveAttribute("href", "/manage/conversationview/conv-bb");
  });

  it("narrows to the silence evidence from the resolution split", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    await userEvent.click(within(resolution).getByTestId("resolution-silence").closest("button")!);
    const rows = await screen.findAllByTestId("health-session-row");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveAttribute("data-evidence", "silence");
  });

  it("explains a 403 REVIEW_DISABLED differently from a 403 FORBIDDEN", async () => {
    const view = renderPanel(HEALTH_AGENTS.noReview);
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("health-drill-resolutionRate"));
    expect(await screen.findByTestId("health-denied-REVIEW_DISABLED")).toBeInTheDocument();
    expect(screen.queryByTestId("health-sessions-table")).toBeNull();
    view.unmount();

    renderPanel(HEALTH_AGENTS.forbidden);
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("health-drill-resolutionRate"));
    expect(await screen.findByTestId("health-denied-FORBIDDEN")).toBeInTheDocument();
    expect(screen.queryByTestId("health-denied-REVIEW_DISABLED")).toBeNull();
  });

  it("pages forward and back, and starts on page one again for another filter", async () => {
    renderPanel(HEALTH_AGENTS.paged);
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("health-drill-resolutionRate"));
    const first = await screen.findAllByTestId("health-session-row");
    expect(first).toHaveLength(1);
    expect(within(first[0]!).getByRole("link")).toHaveAttribute("href", "/manage/conversationview/conv-bb");
    expect(screen.getByTestId("health-sessions-prev")).toBeDisabled();

    await userEvent.click(screen.getByTestId("health-sessions-next"));
    await waitFor(() => expect(within(screen.getAllByTestId("health-session-row")[0]!).getByRole("link")).toHaveAttribute("href", "/manage/conversationview/conv-cc"));
    await userEvent.click(screen.getByTestId("health-sessions-prev"));
    await waitFor(() => expect(within(screen.getAllByTestId("health-session-row")[0]!).getByRole("link")).toHaveAttribute("href", "/manage/conversationview/conv-bb"));

    // Go to page two, close, and open another filter: page one of that filter, never page two of this one.
    await userEvent.click(screen.getByTestId("health-sessions-next"));
    await waitFor(() => expect(screen.getByTestId("health-sessions-prev")).toBeEnabled());
    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByTestId("health-sessions-table")).toBeNull());
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("resolution-agentClosed").closest("button")!);
    const rows = await screen.findAllByTestId("health-session-row");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveAttribute("data-evidence", "agent_closed");
    expect(screen.queryByTestId("health-sessions-prev")).toBeNull();
  });
});

describe("health profile editor", () => {
  const openProfile = async () => {
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-profile"));
    await screen.findByTestId("health-threshold-resolutionRate-red");
  };

  it("shows the server's violation under the field it names", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    const red = screen.getByTestId("health-threshold-resolutionRate-red");
    expect(red).toHaveValue("40");
    await userEvent.clear(red);
    await userEvent.type(red, "70");
    await userEvent.click(screen.getByTestId("health-profile-save"));
    expect(await screen.findByTestId("health-violation-thresholds.resolutionRate")).toHaveTextContent("Red must differ from green.");
    expect(screen.getByTestId("health-profile-form")).toBeInTheDocument();
  });

  it("lists a violation that has no field in a general list instead of dropping it", async () => {
    server.use(
      http.put("*/agents/:agentId/health/profile", () =>
        HttpResponse.json({ violations: [{ path: "somethingNew.deep", message: "A rule the editor has no field for." }] }, { status: 400 }),
      ),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    await userEvent.click(screen.getByTestId("health-profile-save"));
    expect(await screen.findByTestId("health-profile-general-violations")).toHaveTextContent("A rule the editor has no field for.");
  });

  it("keeps what is typed in a number field: 0.5 stays 0.5 and saves as 0.5", async () => {
    let body: { budgets: Record<string, number> } | null = null;
    server.use(
      http.put("*/agents/:agentId/health/profile", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({ ...body, stored: true });
      }),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    const budget = screen.getByTestId("health-budget-costPerResolvedUsd");
    await userEvent.clear(budget);
    await userEvent.type(budget, "0.");
    // The intermediate "0." is not turned back into "0".
    expect(budget).toHaveValue("0.");
    await userEvent.type(budget, "5");
    expect(budget).toHaveValue("0.5");
    await userEvent.click(screen.getByTestId("health-profile-save"));
    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.budgets.costPerResolvedUsd).toBe(0.5);
  });

  it("accepts a decimal comma", async () => {
    let body: { budgets: Record<string, number> } | null = null;
    server.use(
      http.put("*/agents/:agentId/health/profile", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({ ...body, stored: true });
      }),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    const budget = screen.getByTestId("health-budget-costPerResolvedUsd");
    await userEvent.clear(budget);
    await userEvent.type(budget, "0,5");
    expect(budget).toHaveValue("0,5");
    await userEvent.click(screen.getByTestId("health-profile-save"));
    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.budgets.costPerResolvedUsd).toBe(0.5);
  });

  it("refuses to save text that is not a number", async () => {
    let puts = 0;
    server.use(http.put("*/agents/:agentId/health/profile", () => { puts += 1; return HttpResponse.json({}); }));
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    const budget = screen.getByTestId("health-budget-costPerResolvedUsd");
    await userEvent.clear(budget);
    await userEvent.type(budget, "-");
    expect(budget).toHaveValue("-");
    await userEvent.click(screen.getByTestId("health-profile-save"));
    expect(await screen.findByTestId("health-profile-bad-number")).toBeInTheDocument();
    expect(puts).toBe(0);
  });

  it("refuses to save a number too large to be finite, which would serialise as null", async () => {
    let puts = 0;
    server.use(http.put("*/agents/:agentId/health/profile", () => { puts += 1; return HttpResponse.json({}); }));
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    const budget = screen.getByTestId("health-budget-costPerResolvedUsd");
    // 400 digits: Number() reads it as Infinity.
    fireEvent.change(budget, { target: { value: "9".repeat(400) } });
    await userEvent.click(screen.getByTestId("health-profile-save"));
    expect(await screen.findByTestId("health-profile-bad-number")).toBeInTheDocument();
    expect(puts).toBe(0);
  });

  it("offers thresholds and critical flags for measures that are not on screen", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    // fallbackRate has no data in this fixture but is a contract measure.
    expect(screen.getByTestId("health-critical-fallbackRate")).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByTestId("health-add-threshold"), "fallbackRate");
    const green = screen.getByTestId("health-threshold-fallbackRate-green");
    await userEvent.type(green, "5");
    expect(green).toHaveValue("5");
  });

  it("removes a threshold row, keeping the other thresholds and the rest of the profile", async () => {
    let body: { thresholds: Record<string, unknown>; budgets: Record<string, number> } | null = null;
    server.use(
      http.put("*/agents/:agentId/health/profile", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({ ...body, stored: true });
      }),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    await userEvent.click(screen.getByTestId("health-threshold-remove-agentFailureRate"));
    expect(screen.queryByTestId("health-threshold-agentFailureRate")).toBeNull();
    expect(screen.getByTestId("health-threshold-resolutionRate")).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("health-profile-save"));
    await waitFor(() => expect(body).not.toBeNull());
    expect(Object.keys(body!.thresholds)).toEqual(["resolutionRate"]);
    expect(body!.budgets.costPerResolvedUsd).toBe(0.5);
  });

  it("keeps save and reset disabled while either one is in flight", async () => {
    let release: () => void = () => {};
    const held = new Promise<void>((r) => {
      release = r;
    });
    let deletes = 0;
    let puts = 0;
    server.use(
      http.get("*/agents/:agentId/health/profile", () =>
        HttpResponse.json({ stored: true, preset: "support", critical: [], thresholds: { resolutionRate: { green: 0.7, red: 0.4 } }, budgets: {}, slo: {}, overrides: [] }),
      ),
      http.put("*/agents/:agentId/health/profile", async ({ request }) => {
        puts += 1;
        await held;
        return HttpResponse.json({ ...((await request.json()) as object), stored: true });
      }),
      http.delete("*/agents/:agentId/health/profile", () => {
        deletes += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    // Arm the reset first, then save: the armed reset must not fire while the save runs.
    await userEvent.click(screen.getByTestId("health-profile-reset"));
    await userEvent.click(screen.getByTestId("health-profile-save"));
    await waitFor(() => expect(screen.getByTestId("health-profile-save")).toBeDisabled());
    expect(screen.getByTestId("health-profile-reset")).toBeDisabled();
    fireEvent.click(screen.getByTestId("health-profile-reset"));
    fireEvent.submit(screen.getByTestId("health-profile-form"));
    expect(deletes).toBe(0);
    release();
    await waitFor(() => expect(screen.queryByTestId("health-profile-form")).toBeNull());
    expect(deletes).toBe(0);
    expect(puts).toBe(1);
  });

  it("lets an override target a whole section", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    await userEvent.click(screen.getByTestId("health-add-override"));
    const select = within(screen.getByTestId("health-override")).getAllByRole("combobox")[0]!;
    expect(within(select).getByRole("option", { name: "Business outcomes" })).toBeInTheDocument();
  });

  it("shows an error when the reset fails", async () => {
    server.use(http.delete("*/agents/:agentId/health/profile", () => HttpResponse.json({ error: "X", message: "Nope" }, { status: 500 })));
    server.use(
      http.get("*/agents/:agentId/health/profile", () =>
        HttpResponse.json({ stored: true, preset: "support", critical: [], thresholds: { resolutionRate: { green: 0.7, red: 0.4 } }, budgets: {}, slo: {}, overrides: [] }),
      ),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    await userEvent.click(screen.getByTestId("health-profile-reset"));
    await userEvent.click(screen.getByTestId("health-profile-reset"));
    expect(await screen.findByTestId("health-profile-reset-error")).toBeInTheDocument();
  });

  it("saves a valid profile and closes", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await openProfile();
    await userEvent.click(screen.getByTestId("health-profile-save"));
    await waitFor(() => expect(screen.queryByTestId("health-profile-form")).toBeNull());
  });
});

describe("owner report links", () => {
  it("disables the quality section when the creator cannot read ratings", async () => {
    renderPanel(HEALTH_AGENTS.restricted);
    await screen.findByTestId("health-restricted");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    const quality = await screen.findByTestId("health-report-section-quality");
    expect(quality).toBeDisabled();
    expect(quality).not.toBeChecked();
    expect(screen.getByTestId("health-report-quality-note")).toBeInTheDocument();
    expect(screen.getByTestId("health-report-section-reliability")).toBeEnabled();
  });

  it("lets a creator who can read ratings include the quality section", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    expect(await screen.findByTestId("health-report-section-quality")).toBeEnabled();
    expect(screen.queryByTestId("health-report-quality-note")).toBeNull();
  });

  it("shows the link once: it is gone when the dialog is reopened", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    await userEvent.click(await screen.findByTestId("health-report-create"));
    expect(await screen.findByTestId("health-report-created")).toHaveTextContent("https://eddi.example/health-report/token-rep-1");
    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByTestId("health-reports-dialog")).toBeNull());
    await userEvent.click(screen.getByTestId("health-open-reports"));
    await screen.findByTestId("health-report-list");
    expect(screen.queryByTestId("health-report-created")).toBeNull();
  });

  it("takes the created link off the screen once that link is revoked, and only then", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    await userEvent.click(await screen.findByTestId("health-report-create"));
    expect(await screen.findByTestId("health-report-created")).toBeInTheDocument();
    const list = await screen.findByTestId("health-report-list");
    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(1));

    // A failed revoke leaves the link working, so it stays.
    server.use(http.delete("*/agents/:agentId/health/reports/:id", () => HttpResponse.json({ error: "X", message: "Could not revoke" }, { status: 500 }), { once: true }));
    await userEvent.click(within(list).getByRole("button"));
    await userEvent.click(within(list).getByTestId("health-report-revoke-confirm"));
    expect(await screen.findByTestId("health-report-revoke-error")).toBeInTheDocument();
    expect(screen.getByTestId("health-report-created")).toBeInTheDocument();

    await userEvent.click(within(screen.getByTestId("health-report-list")).getByRole("button"));
    await userEvent.click(within(screen.getByTestId("health-report-list")).getByTestId("health-report-revoke-confirm"));
    await waitFor(() => expect(screen.queryByTestId("health-report-created")).toBeNull());
  });

  it("says so when the server did not return the link", async () => {
    server.use(
      http.post("*/agents/:agentId/health/reports", () => HttpResponse.json({ id: "x", expiresAt: "2026-12-01T00:00:00Z", sections: ["reliability"] }, { status: 201 })),
    );
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    await userEvent.click(await screen.findByTestId("health-report-create"));
    expect(await screen.findByTestId("health-report-no-url")).toBeInTheDocument();
    expect(screen.queryByTestId("health-report-created")).toBeNull();
  });

  it("asks before revoking, lists and revokes, and shows a failed revoke", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    await userEvent.click(await screen.findByTestId("health-report-create"));
    const list = await screen.findByTestId("health-report-list");
    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(1));
    // The default selection leaves quality out: sharing ratings is a deliberate choice.
    expect(list).not.toHaveTextContent(/quality/i);

    // First click only asks; nothing is deleted yet.
    await userEvent.click(within(list).getByRole("button"));
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    expect(within(list).getByTestId("health-report-revoke-confirm")).toBeInTheDocument();

    server.use(http.delete("*/agents/:agentId/health/reports/:id", () => HttpResponse.json({ error: "X", message: "Could not revoke" }, { status: 500 })));
    await userEvent.click(within(list).getByTestId("health-report-revoke-confirm"));
    expect(await screen.findByTestId("health-report-revoke-error")).toBeInTheDocument();

    server.resetHandlers();
    await userEvent.click(within(screen.getByTestId("health-report-list")).getByRole("button"));
    await userEvent.click(within(screen.getByTestId("health-report-list")).getByTestId("health-report-revoke-confirm"));
    await waitFor(() => expect(within(screen.getByTestId("health-report-list")).queryAllByRole("listitem")).toHaveLength(0));
  });
});

describe("export", () => {
  it("states visibly, not only in a tooltip, that the export ignores the version and origin filters", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-section-reliability");
    const note = screen.getByTestId("health-export-note");
    expect(note).toBeVisible();
    expect(note).toHaveTextContent("not the version or origin filters");
    expect(screen.getByTestId("health-export-json").closest("[role=group]")).toHaveAttribute("aria-describedby", note.id);
  });

  it("goes through the API client with its auth header, and names the file after the agent and period", async () => {
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => "blob:health");
    const revokeObjectURL = vi.fn();
    const original = { c: URL.createObjectURL, r: URL.revokeObjectURL };
    URL.createObjectURL = createObjectURL as never;
    URL.revokeObjectURL = revokeObjectURL;
    const downloads: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push(this.download);
    });
    let auth: string | null = null;
    server.use(
      http.get("*/agents/:agentId/health/export", ({ request }) => {
        auth = request.headers.get("authorization");
        return new HttpResponse("a,b\n1,2\n", { headers: { "Content-Type": "text/csv" } });
      }),
    );
    api.setAuthToken("tok-123");
    try {
      renderPanel(HEALTH_AGENTS.healthy);
      await screen.findByTestId("health-section-reliability");
      await userEvent.click(screen.getByTestId("health-period-7d"));
      await userEvent.click(screen.getByTestId("health-export-csv"));
      await waitFor(() => expect(downloads).toHaveLength(1));
      expect(auth).toBe("Bearer tok-123");
      expect(downloads[0]).toBe(`agent-health-${HEALTH_AGENTS.healthy}-7d-daily.csv`);
      const blob = createObjectURL.mock.calls[0]![0];
      expect(await blob.text()).toBe("a,b\n1,2\n");
      // Revoked later, not while the browser may still be starting the download.
      expect(revokeObjectURL).not.toHaveBeenCalled();
    } finally {
      api.clearAuthToken();
      click.mockRestore();
      URL.createObjectURL = original.c;
      URL.revokeObjectURL = original.r;
    }
  });
});
