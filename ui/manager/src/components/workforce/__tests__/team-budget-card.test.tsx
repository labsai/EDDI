import { describe, it, expect, beforeEach, vi } from "vitest";
import { act, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderPage } from "@/test/test-utils";
import { resetTeamBudgetMock } from "@/test/mocks/team-budget-handlers";
import { TeamBudgetCard } from "../team-budget-card";
import { TeamBudgetsTable } from "@/components/quotas/team-budgets-table";

const auth = vi.hoisted(() => ({ admin: true }));
vi.mock("@/hooks/use-auth", () => ({
  useIsAdminOrUnknown: () => auth.admin,
}));

function descriptor(id: string, name: string) {
  return {
    resource: `eddi://ai.labs.group/groupstore/groups/${id}?version=1`,
    name,
    description: "",
    createdOn: 1,
    lastModifiedOn: 1,
  };
}

function useGroupList() {
  server.use(
    http.get("*/groupstore/groups/descriptors", () =>
      HttpResponse.json([descriptor("studio", "Content Studio"), descriptor("radar", "Signal Radar"), descriptor("free", "No Budget")]),
    ),
    http.get("*/groupstore/groups/:id", ({ params }) =>
      HttpResponse.json({ name: String(params.id), description: "", members: [], style: "ROUND_TABLE" }),
    ),
  );
}

describe("TeamBudgetCard", () => {
  beforeEach(() => {
    resetTeamBudgetMock();
    auth.admin = true;
  });

  it("shows the cap, what is spent and what remains", async () => {
    renderPage("/manage/groups/studio/workspace", <TeamBudgetCard groupId="studio" />);
    expect(await screen.findByTestId("team-budget-card")).toBeInTheDocument();
    expect(screen.getByTestId("team-budget-cap")).toHaveTextContent("35");
    expect(screen.getByTestId("team-budget-spent")).toHaveTextContent("12.5");
    expect(screen.getByTestId("team-budget-remaining")).toHaveTextContent("22.5");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "36");
  });

  it("renders nothing for a group without a budget, an unknown group, or a forbidden one", async () => {
    const free = renderPage("/x", <TeamBudgetCard groupId="free" />);
    await waitFor(() => expect(free.container.querySelector("[data-testid='team-budget-card']")).toBeNull());
    free.unmount();
    server.use(http.get("*/groupstore/groups/:groupId/budget", () => HttpResponse.json({ error: "no" }, { status: 403 })));
    const forbidden = renderPage("/x", <TeamBudgetCard groupId="studio" />);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(forbidden.container.querySelector("[data-testid='team-budget-card']")).toBeNull();
  });

  it("lists earlier months under History", async () => {
    renderPage("/x", <TeamBudgetCard groupId="studio" />);
    fireEvent.click(await screen.findByTestId("team-budget-history-toggle"));
    const history = await screen.findByTestId("team-budget-history");
    expect(await within(history).findByText("2026-10")).toBeInTheDocument();
  });

  it("flags a spent budget and a tenant-limited one", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/budget", () =>
        HttpResponse.json({
          groupId: "studio", groupName: "Content Studio", month: "2026-11", baseUsd: 35, adjustmentUsd: 0, capUsd: 35, spentUsd: 35,
          remainingUsd: 0, exhausted: true, resetsAt: "2026-12-01T00:00:00Z", tenantRemainingUsd: 0, limitedBy: "TENANT",
        }),
      ),
    );
    renderPage("/x", <TeamBudgetCard groupId="studio" />);
    expect(await screen.findByTestId("team-budget-exhausted")).toBeInTheDocument();
    expect(screen.getByTestId("team-budget-tenant-limited")).toBeInTheDocument();
  });

  it("an administrator adjusts this month's cap with a reason; the card shows the new cap", async () => {
    let sent: { capUsd?: number; reason?: string } | null = null;
    server.use(
      http.put("*/groupstore/groups/:groupId/budget/current-month", async ({ request }) => {
        sent = (await request.json()) as typeof sent;
        return HttpResponse.json({
          groupId: "studio", groupName: "Content Studio", month: "2026-11", baseUsd: 35, adjustmentUsd: 15, capUsd: 50, spentUsd: 12.5,
          remainingUsd: 37.5, exhausted: false, resetsAt: "2026-12-01T00:00:00Z", tenantRemainingUsd: null, limitedBy: "TEAM",
        });
      }),
    );
    renderPage("/x", <TeamBudgetCard groupId="studio" />);
    fireEvent.click(await screen.findByTestId("team-budget-adjust"));
    const dialog = await screen.findByTestId("team-budget-adjust-dialog");
    const save = within(dialog).getByTestId("team-budget-adjust-save");
    fireEvent.change(within(dialog).getByTestId("team-budget-cap-input"), { target: { value: "10" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-reason-input"), { target: { value: "launch week" } });
    expect(save).toBeDisabled(); // below the 12.5 already spent
    fireEvent.change(within(dialog).getByTestId("team-budget-cap-input"), { target: { value: "50" } });
    expect(save).toBeEnabled();
    fireEvent.click(save);
    await waitFor(() => expect(sent).toEqual({ capUsd: 50, reason: "launch week" }));
  });

  it("will not save without a reason", async () => {
    renderPage("/x", <TeamBudgetCard groupId="studio" />);
    fireEvent.click(await screen.findByTestId("team-budget-adjust"));
    const dialog = await screen.findByTestId("team-budget-adjust-dialog");
    fireEvent.change(within(dialog).getByTestId("team-budget-cap-input"), { target: { value: "50" } });
    expect(within(dialog).getByTestId("team-budget-adjust-save")).toBeDisabled();
  });

  it("hides the adjust button from a non-administrator", async () => {
    auth.admin = false;
    renderPage("/x", <TeamBudgetCard groupId="studio" />);
    await screen.findByTestId("team-budget-card");
    expect(screen.queryByTestId("team-budget-adjust")).toBeNull();
  });
});

describe("TeamBudgetsTable", () => {
  beforeEach(() => {
    resetTeamBudgetMock();
    auth.admin = true;
    useGroupList();
  });

  it("lists only the groups that have a budget", async () => {
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    expect(await screen.findByTestId("team-budget-row-studio")).toBeInTheDocument();
    expect(screen.getByTestId("team-budget-row-radar")).toBeInTheDocument();
    expect(screen.queryByTestId("team-budget-row-free")).toBeNull();
  });

  it("says so when no team has a budget", async () => {
    server.use(http.get("*/administration/team-budgets", () => HttpResponse.json({ month: "2026-11", tenantRemainingUsd: null, teams: [] })));
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    expect(await screen.findByTestId("team-budgets-empty")).toBeInTheDocument();
  });

  it("transfers budget with an Idempotency-Key, one per opening", async () => {
    const seen: { key: string | null; body: unknown }[] = [];
    server.use(
      http.post("*/administration/team-budgets/transfers", async ({ request }) => {
        seen.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return HttpResponse.json({ from: {}, to: {}, transferId: "t" });
      }),
    );
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    fireEvent.click(await screen.findByTestId("team-budget-open-transfer"));
    const dialog = await screen.findByTestId("team-budget-transfer-dialog");
    fireEvent.change(within(dialog).getByTestId("team-budget-from"), { target: { value: "studio" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-to"), { target: { value: "radar" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-amount"), { target: { value: "99" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-transfer-reason"), { target: { value: "radar needs it" } });
    expect(within(dialog).getByTestId("team-budget-transfer-save")).toBeDisabled(); // more than studio has left
    fireEvent.change(within(dialog).getByTestId("team-budget-amount"), { target: { value: "5" } });
    fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0]?.key).toMatch(/\S+/);
    expect(seen[0]?.body).toEqual({ fromGroupId: "studio", toGroupId: "radar", amountUsd: 5, reason: "radar needs it" });
  });

  it("will not transfer a team to itself", async () => {
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    fireEvent.click(await screen.findByTestId("team-budget-open-transfer"));
    const dialog = await screen.findByTestId("team-budget-transfer-dialog");
    fireEvent.change(within(dialog).getByTestId("team-budget-from"), { target: { value: "studio" } });
    // the target list leaves the source out
    expect(within(dialog).getByTestId("team-budget-to").querySelector("option[value='studio']")).toBeNull();
  });

  it("shows no adjust or transfer controls to a non-administrator", async () => {
    auth.admin = false;
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    await screen.findByTestId("team-budget-row-studio");
    expect(screen.queryByTestId("team-budget-open-transfer")).toBeNull();
    expect(screen.queryByTestId("team-budget-adjust-studio")).toBeNull();
  });
});
