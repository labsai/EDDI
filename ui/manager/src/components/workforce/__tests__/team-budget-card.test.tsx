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
    // Absence is only meaningful once the answer is in: count the mocked responses,
    // wait for each, let React settle, then assert.
    let answered = 0;
    const onResponse = ({ request }: { request: Request }) => {
      if (/\/groupstore\/groups\/[^/]+\/budget$/.test(new URL(request.url).pathname)) answered++;
    };
    server.events.on("response:mocked", onResponse);
    try {
      for (const [groupId, forbidden] of [["free", false], ["nope", false], ["studio", true]] as const) {
        if (forbidden) {
          server.use(http.get("*/groupstore/groups/:groupId/budget", () => HttpResponse.json({ error: "no" }, { status: 403 })));
        }
        const before = answered;
        const view = renderPage("/x", <TeamBudgetCard groupId={groupId} />);
        await waitFor(() => expect(answered).toBe(before + 1));
        await act(async () => {
          await new Promise((r) => setTimeout(r, 0));
        });
        expect(view.container.querySelector("[data-testid='team-budget-card']")).toBeNull();
        view.unmount();
      }
    } finally {
      server.events.removeListener("response:mocked", onResponse);
    }
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

  it("starts a new Idempotency-Key after a 4xx, keeps it after a 5xx", async () => {
    const keys: (string | null)[] = [];
    const statuses = [503, 400, 200];
    server.use(
      http.post("*/administration/team-budgets/transfers", ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        const status = statuses[keys.length - 1] ?? 200;
        return status === 200
          ? HttpResponse.json({ from: {}, to: {}, transferId: "t" })
          : HttpResponse.json({ error: `failed ${status}` }, { status });
      }),
    );
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    fireEvent.click(await screen.findByTestId("team-budget-open-transfer"));
    const dialog = await screen.findByTestId("team-budget-transfer-dialog");
    fireEvent.change(within(dialog).getByTestId("team-budget-from"), { target: { value: "studio" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-to"), { target: { value: "radar" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-amount"), { target: { value: "5" } });
    fireEvent.change(within(dialog).getByTestId("team-budget-transfer-reason"), { target: { value: "radar needs it" } });
    const save = within(dialog).getByTestId("team-budget-transfer-save");
    for (let attempt = 1; attempt <= 3; attempt++) {
      await waitFor(() => expect(save).toBeEnabled());
      fireEvent.click(save);
      await waitFor(() => expect(keys).toHaveLength(attempt));
      if (attempt < 3) await within(dialog).findByTestId("team-budget-error");
    }
    expect(keys[1]).toBe(keys[0]); // after the 503: same key, the retry stays idempotent
    expect(keys[2]).not.toBe(keys[1]); // after the 400: nothing was applied, a new transfer
  });

  describe("closing the transfer dialog and opening it again", () => {
    /** Answers each transfer with the next status in `statuses` (then 200), recording its key. */
    function answerTransfers(statuses: number[]) {
      const keys: (string | null)[] = [];
      server.use(
        http.post("*/administration/team-budgets/transfers", ({ request }) => {
          keys.push(request.headers.get("Idempotency-Key"));
          const status = statuses[keys.length - 1] ?? 200;
          return status === 200
            ? HttpResponse.json({ from: {}, to: {}, transferId: "t" })
            : HttpResponse.json({ error: `failed ${status}` }, { status });
        }),
      );
      return keys;
    }

    async function openTransfer() {
      fireEvent.click(await screen.findByTestId("team-budget-open-transfer"));
      return screen.findByTestId("team-budget-transfer-dialog");
    }

    function fillTransfer(dialog: HTMLElement) {
      fireEvent.change(within(dialog).getByTestId("team-budget-from"), { target: { value: "studio" } });
      fireEvent.change(within(dialog).getByTestId("team-budget-to"), { target: { value: "radar" } });
      fireEvent.change(within(dialog).getByTestId("team-budget-amount"), { target: { value: "5" } });
      fireEvent.change(within(dialog).getByTestId("team-budget-transfer-reason"), { target: { value: "radar needs it" } });
    }

    async function cancel(dialog: HTMLElement) {
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(screen.queryByTestId("team-budget-transfer-dialog")).toBeNull());
    }

    it("keeps the attempt after a 5xx, so the retry reuses its Idempotency-Key", async () => {
      // A 5xx or a network error may have moved the money. Cancel used to start
      // a fresh key and an empty form, so re-entering the same transfer sent it
      // as a new one and could move the money twice.
      const keys = answerTransfers([503]);
      renderPage("/manage/quotas", <TeamBudgetsTable />);
      let dialog = await openTransfer();
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await within(dialog).findByTestId("team-budget-error");
      await cancel(dialog);

      dialog = await openTransfer();
      // The open question stays on screen, and so does the transfer it is about.
      expect(within(dialog).getByTestId("team-budget-error")).toHaveTextContent("failed 503");
      expect(within(dialog).getByTestId("team-budget-amount")).toHaveValue("5");
      expect(within(dialog).getByTestId("team-budget-to")).toHaveValue("radar");
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(keys).toHaveLength(2));
      expect(keys[1]).toBe(keys[0]);
    });

    it("starts a new transfer after a 4xx, with an empty form and a new key", async () => {
      const keys = answerTransfers([400]);
      renderPage("/manage/quotas", <TeamBudgetsTable />);
      let dialog = await openTransfer();
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await within(dialog).findByTestId("team-budget-error");
      await cancel(dialog);

      dialog = await openTransfer();
      expect(within(dialog).queryByTestId("team-budget-error")).toBeNull();
      expect(within(dialog).getByTestId("team-budget-amount")).toHaveValue("");
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(keys).toHaveLength(2));
      expect(keys[1]).not.toBe(keys[0]);
    });

    it("starts a new transfer after a success, even one that followed a 5xx", async () => {
      // The retry that succeeds still has the 5xx's "may have been applied" flag
      // behind it; the success settles it, so nothing of that attempt is kept.
      const keys = answerTransfers([503]);
      renderPage("/manage/quotas", <TeamBudgetsTable />);
      let dialog = await openTransfer();
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await within(dialog).findByTestId("team-budget-error");
      await waitFor(() => expect(within(dialog).getByTestId("team-budget-transfer-save")).toBeEnabled());
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(screen.queryByTestId("team-budget-transfer-dialog")).toBeNull());
      expect(keys[1]).toBe(keys[0]);

      dialog = await openTransfer();
      expect(within(dialog).queryByTestId("team-budget-error")).toBeNull();
      expect(within(dialog).getByTestId("team-budget-amount")).toHaveValue("");
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(keys).toHaveLength(3));
      expect(keys[2]).not.toBe(keys[1]);
    });

    it("starts a new transfer after a success", async () => {
      const keys = answerTransfers([]);
      renderPage("/manage/quotas", <TeamBudgetsTable />);
      let dialog = await openTransfer();
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(screen.queryByTestId("team-budget-transfer-dialog")).toBeNull());

      dialog = await openTransfer();
      expect(within(dialog).getByTestId("team-budget-amount")).toHaveValue("");
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(keys).toHaveLength(2));
      expect(keys[1]).not.toBe(keys[0]);
    });

    it("keeps the attempt when closed while the transfer is still running", async () => {
      // Closing mid-request is the same open question as a 5xx: the money may
      // move. Resetting there replaced the key the request carried, so the 5xx
      // that followed left a retry with a new key that could move it twice.
      const keys: (string | null)[] = [];
      let release!: () => void;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      server.use(
        http.post("*/administration/team-budgets/transfers", async ({ request }) => {
          keys.push(request.headers.get("Idempotency-Key"));
          if (keys.length > 1) return HttpResponse.json({ from: {}, to: {}, transferId: "t" });
          await held;
          return HttpResponse.json({ error: "failed 503" }, { status: 503 });
        }),
      );
      renderPage("/manage/quotas", <TeamBudgetsTable />);
      let dialog = await openTransfer();
      fillTransfer(dialog);
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(keys).toHaveLength(1));
      await cancel(dialog);
      await act(async () => {
        release();
        await new Promise((r) => setTimeout(r, 50));
      });

      dialog = await openTransfer();
      expect(within(dialog).getByTestId("team-budget-error")).toHaveTextContent("failed 503");
      expect(within(dialog).getByTestId("team-budget-amount")).toHaveValue("5");
      fireEvent.click(within(dialog).getByTestId("team-budget-transfer-save"));
      await waitFor(() => expect(keys).toHaveLength(2));
      expect(keys[1]).toBe(keys[0]);
    });
  });

  it("shows a budget-read failure with a retry instead of hiding the table", async () => {
    let calls = 0;
    server.use(
      http.get("*/administration/team-budgets", () => {
        calls++;
        return calls === 1
          ? HttpResponse.json({ error: "down" }, { status: 503 })
          : HttpResponse.json({ month: "2026-11", tenantRemainingUsd: null, teams: [] });
      }),
    );
    renderPage("/manage/quotas", <TeamBudgetsTable />);
    expect(await screen.findByTestId("team-budgets-error")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("team-budgets-retry"));
    expect(await screen.findByTestId("team-budgets-empty")).toBeInTheDocument();
  });

  it("keeps the cached table and an open transfer dialog when a background poll fails", async () => {
    const { queryClient } = renderPage("/manage/quotas", <TeamBudgetsTable />);
    fireEvent.click(await screen.findByTestId("team-budget-open-transfer"));
    const dialog = await screen.findByTestId("team-budget-transfer-dialog");
    server.use(http.get("*/administration/team-budgets", () => HttpResponse.json({ error: "down" }, { status: 503 })));
    await act(async () => {
      await queryClient.refetchQueries();
    });
    expect(await screen.findByTestId("team-budgets-stale")).toBeInTheDocument();
    expect(screen.queryByTestId("team-budgets-error")).toBeNull();
    expect(screen.getByTestId("team-budget-row-studio")).toBeInTheDocument();
    expect(dialog).toBeInTheDocument();
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
