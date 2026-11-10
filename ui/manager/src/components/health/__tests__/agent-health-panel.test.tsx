import { afterEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { HEALTH_AGENTS } from "@/test/mocks/health-fixtures";
import { resetHealthMockState } from "@/test/mocks/health-handlers";
import { AgentHealthPanel } from "../agent-health-panel";

afterEach(() => resetHealthMockState());

function renderPanel(agentId: string, props: { canEdit?: boolean } = {}) {
  return renderWithProviders(<AgentHealthPanel agentId={agentId} latestVersion={8} canEdit={props.canEdit ?? true} defaultOpen />);
}

const measureCard = async (id: string) => {
  const cards = await screen.findAllByTestId("health-measure");
  const card = cards.find((c) => c.getAttribute("data-measure-id") === id);
  if (!card) throw new Error(`no card for ${id}`);
  return card;
};

describe("AgentHealthPanel", () => {
  it("stays collapsed, and fetches nothing, until it is opened", async () => {
    renderWithProviders(<AgentHealthPanel agentId={HEALTH_AGENTS.healthy} latestVersion={8} canEdit />);
    expect(screen.queryByTestId("health-filters")).toBeNull();
    await userEvent.click(screen.getByTestId("agent-health-toggle"));
    expect(await screen.findByTestId("health-status")).toBeInTheDocument();
  });

  it("shows a healthy agent: status, six sections, values in their units and the golden check", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    expect(await screen.findByTestId("health-status-ring")).toBeInTheDocument();
    expect(screen.getByText("Healthy")).toBeInTheDocument();
    for (const id of ["reliability", "effectiveness", "quality", "efficiency", "adoption", "business"]) {
      expect(await screen.findByTestId(`health-section-${id}`)).toBeInTheDocument();
    }
    const failure = await measureCard("agentFailureRate");
    expect(within(failure).getByTestId("health-value")).toHaveTextContent("0.4%");
    expect(within(failure).getByTestId("health-interval")).toHaveTextContent("0.2%–0.7%");
    expect(within(await measureCard("turnDurationP95")).getByTestId("health-value")).toHaveTextContent("3.20s");
    expect(within(await measureCard("costPerSession")).getByTestId("health-value")).toHaveTextContent("$0.03");
    expect(screen.getByTestId("health-check")).toHaveTextContent("Golden conversations");
    expect(screen.getByTestId("health-check")).toHaveAttribute("data-state", "PASS");
    expect(screen.getByTestId("health-data-through")).toHaveTextContent("2026-11-10");
  });

  it("explains an agent that needs attention with its reasons and signed deltas", async () => {
    renderPanel(HEALTH_AGENTS.attention);
    const status = await screen.findByTestId("health-status");
    expect(within(status).getByText("Needs attention")).toBeInTheDocument();
    expect(within(screen.getByTestId("health-reasons")).getByText(/Resolution 41 %/)).toBeInTheDocument();
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("health-band")).toHaveAttribute("data-band", "RED");
    const delta = within(resolution).getByTestId("health-delta");
    expect(delta).toHaveTextContent("−12 pp");
    expect(delta).toHaveAttribute("data-tone", "worse");
    // A failed, stale golden check is a badge with its age, not colour alone.
    expect(screen.getByTestId("health-check")).toHaveAttribute("data-state", "FAIL");
    expect(screen.getByTestId("health-check-stale")).toBeInTheDocument();
  });

  it("shows the resolution rate split into confirmed, agent closed and silence", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    expect(within(resolution).getByTestId("resolution-confirmed")).toHaveTextContent("39%");
    expect(within(resolution).getByTestId("resolution-agentClosed")).toHaveTextContent("20%");
    expect(within(resolution).getByTestId("resolution-silence")).toHaveTextContent("12%");
    expect(within(resolution).queryByTestId("resolution-silence-warning")).toBeNull();
  });

  it("warns when most of the resolutions are assumed from silence", async () => {
    renderPanel(HEALTH_AGENTS.silence);
    const resolution = await measureCard("resolutionRate");
    const warning = within(resolution).getByTestId("resolution-silence-warning");
    expect(warning).toHaveTextContent("Mostly assumed from silence");
    expect(warning).toHaveTextContent("71 %");
  });

  it("renders a not-enough-data agent with grey measures and their hints, no values", async () => {
    renderPanel(HEALTH_AGENTS.noData);
    expect(await screen.findByText("Not enough data")).toBeInTheDocument();
    const failure = await measureCard("agentFailureRate");
    expect(within(failure).getByTestId("health-band")).toHaveAttribute("data-band", "GREY");
    expect(within(failure).getByTestId("health-value")).toHaveTextContent("—");
    expect(within(failure).getByTestId("health-hint")).toHaveTextContent("Fewer than 30 engaged sessions");
    expect(within(failure).queryByTestId("health-thresholds")).toBeNull();
  });

  it("shows an access message for a restricted section instead of measures", async () => {
    renderPanel(HEALTH_AGENTS.restricted);
    const quality = await screen.findByTestId("health-section-quality");
    expect(within(quality).getByTestId("health-restricted")).toHaveTextContent("You don't have access to this section.");
    expect(within(quality).queryByTestId("health-measure")).toBeNull();
    // The other sections are unaffected.
    expect(within(await screen.findByTestId("health-section-reliability")).getAllByTestId("health-measure").length).toBeGreaterThan(0);
  });

  it("hides the status ring while the backend sends no status", async () => {
    renderPanel(HEALTH_AGENTS.preH4);
    await screen.findByTestId("health-section-reliability");
    expect(screen.queryByTestId("health-status")).toBeNull();
    expect(screen.queryByTestId("health-status-ring")).toBeNull();
  });

  it("draws a sparkline with change markers for a measure", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const failure = await measureCard("agentFailureRate");
    await waitFor(() => expect(within(failure).getByTestId("health-sparkline")).toBeInTheDocument());
    expect(within(failure).getAllByTestId("health-marker").length).toBeGreaterThan(0);
  });

  it("offers the outcome mapping from the coverage measure and explains how to apply it", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const coverage = await measureCard("outcomeCoverage");
    await userEvent.click(within(coverage).getByTestId("health-map-outcomes"));
    const dialog = await screen.findByTestId("health-suggestions-dialog");
    expect(await within(dialog).findByText("order_confirmed")).toBeInTheDocument();
    expect(within(dialog).getByText(/healthConfig\.outcomes/)).toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: /agent's configuration/ })).toHaveAttribute(
      "href",
      `/manage/agentview/${HEALTH_AGENTS.healthy}`,
    );
  });

  it("hides the profile and report links from someone who cannot edit", async () => {
    renderPanel(HEALTH_AGENTS.healthy, { canEdit: false });
    await screen.findByTestId("health-status");
    expect(screen.queryByTestId("health-open-profile")).toBeNull();
    expect(screen.queryByTestId("health-open-reports")).toBeNull();
    expect(screen.getByTestId("health-open-compare")).toBeInTheDocument();
    expect(screen.getByTestId("health-export-json")).toBeInTheDocument();
    expect(screen.getByTestId("health-export-csv")).toBeInTheDocument();
  });

  it("changes the period from the filter bar", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-status");
    expect(screen.getByTestId("health-period-30d")).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByTestId("health-period-7d"));
    expect(screen.getByTestId("health-period-7d")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("health-period-30d")).toHaveAttribute("aria-pressed", "false");
  });
});

describe("version compare", () => {
  it("lists deltas with a verdict and keeps an insignificant change neutral", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-status");
    await userEvent.click(screen.getByTestId("health-open-compare"));
    const table = await screen.findByTestId("health-compare-table");
    const rows = within(table).getAllByTestId("health-compare-row");
    const verdicts = Object.fromEntries(rows.map((r) => [r.textContent?.slice(0, 12), r.getAttribute("data-verdict")]));
    expect(rows.find((r) => r.textContent?.startsWith("Resolution"))).toHaveAttribute("data-verdict", "worse");
    expect(rows.find((r) => r.textContent?.startsWith("Cost per session"))).toHaveAttribute("data-verdict", "better");
    expect(rows.find((r) => r.textContent?.startsWith("Turn duration"))).toHaveAttribute("data-verdict", "neutral");
    expect(Object.keys(verdicts).length).toBe(rows.length);
  });
});

describe("session drill-down", () => {
  it("lists the sessions behind a measure and links each to the conversation view", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    await userEvent.click(within(resolution).getByTestId("health-drill-resolutionRate"));
    const table = await screen.findByTestId("health-sessions-table");
    const rows = within(table).getAllByTestId("health-session-row");
    // Resolved sessions only: the abandoned one is filtered out by the outcome filter.
    expect(rows).toHaveLength(3);
    expect(within(rows[0]!).getByRole("link", { name: /Open/ })).toHaveAttribute("href", "/manage/conversationview/conv-bb");
  });

  it("narrows to the silence evidence from the resolution split", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    const resolution = await measureCard("resolutionRate");
    await userEvent.click(within(resolution).getByRole("button", { name: /Silence/ }));
    const rows = await screen.findAllByTestId("health-session-row");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("silence");
  });

  it("explains a 403 REVIEW_DISABLED differently from a 403 FORBIDDEN", async () => {
    const view = renderPanel(HEALTH_AGENTS.noReview);
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("health-drill-resolutionRate"));
    const denied = await screen.findByTestId("health-denied-REVIEW_DISABLED");
    expect(denied).toHaveTextContent("Conversation review is switched off for this version");
    expect(screen.queryByTestId("health-sessions-table")).toBeNull();
    view.unmount();

    renderPanel(HEALTH_AGENTS.forbidden);
    await userEvent.click(within(await measureCard("resolutionRate")).getByTestId("health-drill-resolutionRate"));
    const forbidden = await screen.findByTestId("health-denied-FORBIDDEN");
    expect(forbidden).toHaveTextContent("You may not read this agent's conversations");
    expect(screen.queryByTestId("health-denied-REVIEW_DISABLED")).toBeNull();
  });
});

describe("health profile editor", () => {
  it("shows the server's violation under the field it names", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-status");
    await userEvent.click(screen.getByTestId("health-open-profile"));
    const red = await screen.findByLabelText("Resolution: red threshold");
    expect(red).toHaveValue("40");
    await userEvent.clear(red);
    await userEvent.type(red, "70");
    await userEvent.click(screen.getByTestId("health-profile-save"));
    const violation = await screen.findByTestId("health-violation-thresholds.resolutionRate");
    expect(violation).toHaveTextContent("Red must differ from green.");
    // The dialog stays open so the person can fix it.
    expect(screen.getByTestId("health-profile-form")).toBeInTheDocument();
  });

  it("shows a violation on an override that lacks its reason", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-status");
    await userEvent.click(screen.getByTestId("health-open-profile"));
    await userEvent.click(await screen.findByRole("button", { name: /Add override/ }));
    await userEvent.click(screen.getByTestId("health-profile-save"));
    expect(await screen.findByTestId("health-violation-overrides[0]")).toHaveTextContent("A reason is required.");
  });

  it("saves a valid profile and closes", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-status");
    await userEvent.click(screen.getByTestId("health-open-profile"));
    await screen.findByLabelText("Resolution: red threshold");
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
    await screen.findByTestId("health-status");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    const quality = await screen.findByTestId("health-report-section-quality");
    expect(quality).toBeEnabled();
    expect(screen.queryByTestId("health-report-quality-note")).toBeNull();
  });

  it("creates a link, shows it once, lists it and revokes it", async () => {
    renderPanel(HEALTH_AGENTS.healthy);
    await screen.findByTestId("health-status");
    await userEvent.click(screen.getByTestId("health-open-reports"));
    await userEvent.click(await screen.findByTestId("health-report-create"));
    expect(await screen.findByTestId("health-report-created")).toHaveTextContent("https://eddi.example/health-report/token-rep-1");
    const list = await screen.findByTestId("health-report-list");
    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(1));
    // The default selection leaves quality out: sharing ratings is a deliberate choice.
    expect(within(list).getByText(/Reliability/)).toBeInTheDocument();
    expect(within(list).queryByText(/Quality/)).toBeNull();
    await userEvent.click(within(list).getByRole("button", { name: /Revoke the link/ }));
    await waitFor(() => expect(within(screen.getByTestId("health-report-list")).queryAllByRole("listitem")).toHaveLength(0));
  });
});
