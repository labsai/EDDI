import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { act, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderPage } from "@/test/test-utils";
import { sampleSpendControlChange, seedSpendControlMock } from "@/test/mocks/spend-control-handlers";
import { PendingSpendChanges } from "../pending-spend-changes";

describe("PendingSpendChanges", () => {
  beforeEach(() => seedSpendControlMock([sampleSpendControlChange, { ...sampleSpendControlChange, id: "q-1", kind: "TENANT_QUOTA", tenantId: "default" }]));
  afterEach(() => seedSpendControlMock());

  it("lists only the changes of its kind, with the loosening parts", async () => {
    renderPage("/manage/model-prices", <PendingSpendChanges kind="MODEL_PRICES" isAdmin />);
    expect(await screen.findByTestId("spend-change-c-1")).toBeInTheDocument();
    expect(screen.queryByTestId("spend-change-q-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("spend-change-loosening-c-1")).toHaveTextContent("gpt-4o");
  });

  it("renders nothing and asks nothing for a non-admin", async () => {
    let asked = false;
    server.use(
      http.get("*/administration/spend-control-changes", () => {
        asked = true;
        return HttpResponse.json([]);
      }),
    );
    renderPage("/manage/model-prices", <PendingSpendChanges kind="MODEL_PRICES" isAdmin={false} />);
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId("spend-changes-MODEL_PRICES")).not.toBeInTheDocument();
    expect(asked).toBe(false);
  });

  it("approves only after the confirmation, then the change leaves the list", async () => {
    let approved: string | null = null;
    server.use(
      http.post("*/administration/spend-control-changes/:id/approve", ({ params }) => {
        approved = String(params.id);
        seedSpendControlMock([]);
        return HttpResponse.json({ ...sampleSpendControlChange, status: "APPLIED" });
      }),
    );
    renderPage("/manage/model-prices", <PendingSpendChanges kind="MODEL_PRICES" isAdmin />);
    fireEvent.click(await screen.findByTestId("spend-change-approve-c-1"));
    expect(approved).toBeNull();
    fireEvent.click(screen.getByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(approved).toBe("c-1"));
    await waitFor(() => expect(screen.queryByTestId("spend-change-c-1")).not.toBeInTheDocument());
  });

  it("rejects through the reject endpoint", async () => {
    const rejected: string[] = [];
    const approved: string[] = [];
    server.use(
      http.post("*/administration/spend-control-changes/:id/reject", ({ params }) => {
        rejected.push(String(params.id));
        seedSpendControlMock([]);
        return HttpResponse.json({ ...sampleSpendControlChange, id: String(params.id), status: "REJECTED" });
      }),
      http.post("*/administration/spend-control-changes/:id/approve", ({ params }) => {
        approved.push(String(params.id));
        return HttpResponse.json({ ...sampleSpendControlChange, status: "APPLIED" });
      }),
    );
    renderPage("/manage/quotas", <PendingSpendChanges kind="TENANT_QUOTA" isAdmin />);
    fireEvent.click(await screen.findByTestId("spend-change-reject-q-1"));
    fireEvent.click(screen.getByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(rejected).toEqual(["q-1"]));
    await waitFor(() => expect(screen.queryByTestId("spend-change-q-1")).not.toBeInTheDocument());
    expect(approved).toEqual([]);
  });

  it("shows a failed first read with a retry instead of looking like no proposals", async () => {
    let fail = true;
    server.use(
      http.get("*/administration/spend-control-changes", () =>
        fail
          ? HttpResponse.json({ error: "boom" }, { status: 500 })
          : HttpResponse.json([sampleSpendControlChange]),
      ),
    );
    renderPage("/manage/model-prices", <PendingSpendChanges kind="MODEL_PRICES" isAdmin />);
    const panel = await screen.findByTestId("spend-changes-MODEL_PRICES-error");
    fail = false;
    fireEvent.click(within(panel).getByTestId("error-state-retry"));
    expect(await screen.findByTestId("spend-change-c-1")).toBeInTheDocument();
  });

  it("renders nothing when the read is refused with 403", async () => {
    server.use(http.get("*/administration/spend-control-changes", () => HttpResponse.json({ error: "no" }, { status: 403 })));
    renderPage("/manage/model-prices", <PendingSpendChanges kind="MODEL_PRICES" isAdmin />);
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId("spend-changes-MODEL_PRICES-error")).not.toBeInTheDocument();
    expect(screen.queryByTestId("spend-changes-MODEL_PRICES")).not.toBeInTheDocument();
  });

  it("drops a selection that another administrator decided, so a later proposal does not reopen it", async () => {
    let approved: string | null = null;
    server.use(
      http.post("*/administration/spend-control-changes/:id/approve", ({ params }) => {
        approved = String(params.id);
        return HttpResponse.json({ ...sampleSpendControlChange, status: "APPLIED" });
      }),
    );
    const { queryClient } = renderPage("/manage/model-prices", <PendingSpendChanges kind="MODEL_PRICES" isAdmin />);
    fireEvent.click(await screen.findByTestId("spend-change-approve-c-1"));
    expect(screen.getByTestId("alert-dialog-confirm")).toBeInTheDocument();

    // decided elsewhere: the next poll no longer lists it
    seedSpendControlMock([]);
    await act(() => queryClient.invalidateQueries({ queryKey: ["spend-control"] }));
    await waitFor(() => expect(screen.queryByTestId("spend-change-c-1")).not.toBeInTheDocument());

    // a new proposal arrives
    seedSpendControlMock([{ ...sampleSpendControlChange, id: "c-2" }]);
    await act(() => queryClient.invalidateQueries({ queryKey: ["spend-control"] }));
    expect(await screen.findByTestId("spend-change-c-2")).toBeInTheDocument();
    expect(screen.queryByTestId("alert-dialog-confirm")).not.toBeInTheDocument();
    expect(approved).toBeNull();
  });

  it("disables Approve and says why while approving is blocked; Reject still works", async () => {
    renderPage("/manage/quotas", <PendingSpendChanges kind="TENANT_QUOTA" isAdmin approveBlockedReason="Save your quota edits first" />);
    expect(await screen.findByTestId("spend-change-approve-q-1")).toBeDisabled();
    expect(screen.getByTestId("spend-change-approve-blocked-q-1")).toHaveTextContent("Save your quota edits first");
    expect(screen.getByTestId("spend-change-reject-q-1")).toBeEnabled();
  });
});
