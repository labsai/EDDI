import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
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
