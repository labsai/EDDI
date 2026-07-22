import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalsPage } from "@/pages/approvals";
import type { PendingApprovalSummary } from "@/lib/api/hitl";

function mockInbox(regular: PendingApprovalSummary[]) {
  server.use(
    http.get("*/agents/pending-approvals", () => HttpResponse.json(regular)),
    http.get("*/groups/pending-approvals", () => HttpResponse.json([])),
  );
}

const rulePause: PendingApprovalSummary = {
  conversationId: "conv-rule-1",
  agentId: "agent-1",
  pausedAt: "2026-07-01T10:00:00.000Z",
  pauseReason: "Deletion needs sign-off",
  timeoutPolicy: "WAIT_INDEFINITELY",
  pauseType: "RULE",
};

const toolPause: PendingApprovalSummary = {
  conversationId: "conv-tool-1",
  agentId: "agent-1",
  pausedAt: "2026-07-01T10:05:00.000Z",
  pauseReason: "Approval required",
  timeoutPolicy: "AUTO_REJECT",
  pauseType: "TOOL_CALL",
  toolNames: ["sendEmail", "transfer_funds"],
};

describe("ApprovalsPage — tool-call pauses", () => {
  it("badges a TOOL_CALL pause and routes it to Review, not a blind quick-approve", async () => {
    mockInbox([toolPause]);
    renderWithProviders(<ApprovalsPage />);

    expect(await screen.findByTestId("tool-badge-conv-tool-1")).toBeInTheDocument();
    // Tool names replace the generic reason so the reviewer sees what's gated.
    expect(screen.getByText("sendEmail, transfer_funds")).toBeInTheDocument();
    // Safe: a Review link, and NO inline Approve/Reject that would approve blind.
    expect(screen.getByTestId("review-conv-tool-1")).toBeInTheDocument();
    expect(screen.queryByTestId("approve-conv-tool-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-conv-tool-1")).not.toBeInTheDocument();
    // Cancel stays available.
    expect(screen.getByTestId("cancel-conv-tool-1")).toBeInTheDocument();
  });

  it("keeps inline Approve/Reject for a RULE pause and shows no tool badge", async () => {
    mockInbox([rulePause]);
    renderWithProviders(<ApprovalsPage />);

    expect(await screen.findByTestId("approve-conv-rule-1")).toBeInTheDocument();
    expect(screen.getByTestId("reject-conv-rule-1")).toBeInTheDocument();
    expect(screen.queryByTestId("tool-badge-conv-rule-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("review-conv-rule-1")).not.toBeInTheDocument();
  });

  it("shows the RBAC scope banner (all approvals under the no-auth guest context)", async () => {
    mockInbox([]);
    renderWithProviders(<ApprovalsPage />);
    // GUEST_CONTEXT has method "none" → useHasRole returns true → admin/approver scope.
    const scope = await screen.findByTestId("approvals-scope");
    expect(scope).toHaveTextContent(/all pending approvals/i);
  });
});

describe("ApprovalsPage — confirmation gate on irreversible queue actions", () => {
  it("Approve on a RULE pause confirms first; the resume fires only after confirming", async () => {
    const user = userEvent.setup();
    let resumeBody: unknown = null;
    server.use(
      http.post("*/agents/:conversationId/resume", async ({ request }) => {
        resumeBody = await request.json().catch(() => ({}));
        return new HttpResponse(null, { status: 200 });
      }),
    );
    mockInbox([rulePause]);
    renderWithProviders(<ApprovalsPage />);

    await user.click(await screen.findByTestId("approve-conv-rule-1"));
    // A single click must NOT resume the conversation.
    expect(resumeBody).toBeNull();

    // A confirmation dialog is shown.
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Approve and resume this conversation?")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(resumeBody).toEqual({ verdict: "APPROVED" }));
  });

  it("Reject confirms first; nothing is sent until the reviewer confirms", async () => {
    const user = userEvent.setup();
    const resumeSpy = vi.fn();
    server.use(
      http.post("*/agents/:conversationId/resume", async ({ request }) => {
        resumeSpy(await request.json().catch(() => ({})));
        return new HttpResponse(null, { status: 200 });
      }),
    );
    mockInbox([rulePause]);
    renderWithProviders(<ApprovalsPage />);

    await user.click(await screen.findByTestId("reject-conv-rule-1"));
    expect(resumeSpy).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(resumeSpy).toHaveBeenCalledWith({ verdict: "REJECTED" }));
  });

  it("dismissing the Reject confirmation does not send a decision", async () => {
    const user = userEvent.setup();
    const resumeSpy = vi.fn();
    server.use(
      http.post("*/agents/:conversationId/resume", () => {
        resumeSpy();
        return new HttpResponse(null, { status: 200 });
      }),
    );
    mockInbox([rulePause]);
    renderWithProviders(<ApprovalsPage />);

    await user.click(await screen.findByTestId("reject-conv-rule-1"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Go back" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(resumeSpy).not.toHaveBeenCalled();
  });

  it("Cancel confirms first; the cancel endpoint fires only after confirming", async () => {
    const user = userEvent.setup();
    const cancelSpy = vi.fn();
    server.use(
      http.post("*/agents/:conversationId/cancel", () => {
        cancelSpy();
        return new HttpResponse(null, { status: 200 });
      }),
    );
    mockInbox([rulePause]);
    renderWithProviders(<ApprovalsPage />);

    await user.click(await screen.findByTestId("cancel-conv-rule-1"));
    // A single click must NOT abort the conversation.
    expect(cancelSpy).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel conversation" }));
    await waitFor(() => expect(cancelSpy).toHaveBeenCalledTimes(1));
  });
});
