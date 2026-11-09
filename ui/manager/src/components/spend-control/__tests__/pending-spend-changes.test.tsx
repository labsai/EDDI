import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { screen, fireEvent, waitFor } from "@testing-library/react";
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
    renderPage("/manage/quotas", <PendingSpendChanges kind="TENANT_QUOTA" isAdmin />);
    fireEvent.click(await screen.findByTestId("spend-change-reject-q-1"));
    fireEvent.click(screen.getByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(screen.queryByTestId("spend-change-q-1")).not.toBeInTheDocument());
  });
});
