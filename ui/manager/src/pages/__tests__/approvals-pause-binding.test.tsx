import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalsPage } from "@/pages/approvals";
import type { PendingApprovalSummary } from "@/lib/api/hitl";

/**
 * UI review High 8: the queue approved from a row up to ten seconds old and sent
 * a bare verdict, which the backend applies to whatever the conversation is
 * paused on when it arrives. A rule pause resolved elsewhere and re-paused on
 * gated tool calls was then approved wholesale — every call, none reviewed.
 */

const toastMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: toastMock }));

beforeEach(() => {
  for (const fn of Object.values(toastMock)) fn.mockClear();
});

const rulePause: PendingApprovalSummary = {
  conversationId: "conv-rule-1",
  agentId: "agent-1",
  pausedAt: "2026-07-01T10:00:00.000Z",
  pauseReason: "Deletion needs sign-off",
  timeoutPolicy: "WAIT_INDEFINITELY",
  pauseType: "RULE",
};

const secondRulePause: PendingApprovalSummary = {
  ...rulePause,
  conversationId: "conv-rule-2",
};

function serveInbox(items: PendingApprovalSummary[]) {
  server.use(
    http.get("*/agents/pending-approvals", () => HttpResponse.json(items)),
    http.get("*/groups/pending-approvals", () => HttpResponse.json([])),
  );
}

function serveStatus(conversationId: string, body: Record<string, unknown>) {
  server.use(
    http.get(`*/agents/${conversationId}/approval-status`, () =>
      HttpResponse.json({ conversationId, state: "AWAITING_HUMAN", ...body }),
    ),
  );
}

function recordResumes() {
  const resumes: { id: string; body: Record<string, unknown> }[] = [];
  server.use(
    http.post("*/agents/:conversationId/resume", async ({ request, params }) => {
      resumes.push({
        id: params.conversationId as string,
        body: (await request.json()) as Record<string, unknown>,
      });
      return new HttpResponse(null, { status: 200 });
    }),
  );
  return resumes;
}

async function approveRow(user: ReturnType<typeof userEvent.setup>, id: string) {
  await user.click(await screen.findByTestId(`approve-${id}`));
  const dialog = await screen.findByRole("dialog");
  await user.click(within(dialog).getByRole("button", { name: "Approve" }));
}

describe("queue decisions are bound to the pause the row showed", () => {
  it("refuses a rule-row approval once the conversation re-paused on tool calls", async () => {
    const resumes = recordResumes();
    serveInbox([rulePause]);
    // Resolved elsewhere and paused again — on gated calls, a minute later.
    serveStatus("conv-rule-1", {
      pausedAt: "2026-07-01T10:01:00.000Z",
      pauseDetails: {
        type: "TOOL_CALL",
        calls: [{ callId: "c1", toolName: "transfer_funds", source: "http", arguments: "{}", argsTruncated: false }],
        executedUngatedCalls: [],
        outcomeUnknown: [],
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    await approveRow(user, "conv-rule-1");

    await waitFor(() =>
      expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/changed since you opened it/)),
    );
    expect(resumes).toEqual([]);
    expect(toastMock.success).not.toHaveBeenCalled();
  });

  it("refuses when the same kind of pause started at a different time", async () => {
    const resumes = recordResumes();
    serveInbox([rulePause]);
    serveStatus("conv-rule-1", {
      pausedAt: "2026-07-01T10:02:00.000Z",
      pauseDetails: { type: "RULE", reason: "again", actions: [] },
    });
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    await approveRow(user, "conv-rule-1");

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled());
    expect(resumes).toEqual([]);
  });

  it("sends the pause id when the backend reports one, so it can refuse a later pause itself", async () => {
    const resumes = recordResumes();
    serveInbox([rulePause]);
    serveStatus("conv-rule-1", {
      pausedAt: rulePause.pausedAt,
      pauseId: String(Date.parse(rulePause.pausedAt)),
      pauseDetails: { type: "RULE", reason: null, actions: [] },
    });
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    await approveRow(user, "conv-rule-1");

    await waitFor(() => expect(resumes).toHaveLength(1));
    expect(resumes[0]!.body).toEqual({
      verdict: "APPROVED",
      pauseId: String(Date.parse(rulePause.pausedAt)),
    });
  });

  it("reports the backend's pause-changed 409 as a changed request, not a generic failure", async () => {
    serveInbox([rulePause]);
    serveStatus("conv-rule-1", {
      pausedAt: rulePause.pausedAt,
      pauseId: "1",
      pauseDetails: { type: "RULE", reason: null, actions: [] },
    });
    server.use(
      http.post("*/agents/:conversationId/resume", () =>
        new HttpResponse(
          "The pending approval changed since this decision was made (pauseId no longer current) — re-read approval-status and decide again.",
          { status: 409, headers: { "Content-Type": "text/plain" } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    await approveRow(user, "conv-rule-1");

    await waitFor(() =>
      expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/changed since you opened it/)),
    );
  });

  it("binds an inline tool-call decision to the calls that were shown", async () => {
    const resumes = recordResumes();
    const toolPause: PendingApprovalSummary = {
      conversationId: "conv-tool-1",
      agentId: "agent-1",
      pausedAt: "2026-07-01T10:05:00.000Z",
      pauseReason: "Approval required",
      timeoutPolicy: "AUTO_REJECT",
      pauseType: "TOOL_CALL",
      toolNames: ["sendEmail"],
    };
    serveInbox([toolPause]);
    const shown = {
      pausedAt: toolPause.pausedAt,
      pauseDetails: {
        type: "TOOL_CALL",
        calls: [{ callId: "call-1", toolName: "sendEmail", source: "builtin", arguments: "{}", argsTruncated: false }],
        executedUngatedCalls: [],
        outcomeUnknown: [],
      },
    };
    serveStatus("conv-tool-1", shown);
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    await user.click(await screen.findByTestId("review-conv-tool-1"));
    await user.click(await screen.findByTestId("tool-approve-call-1"));
    // Meanwhile the batch was decided elsewhere and a new one — same time
    // resolution, different call — is waiting.
    serveStatus("conv-tool-1", {
      ...shown,
      pauseDetails: {
        ...shown.pauseDetails,
        calls: [{ callId: "call-9", toolName: "transfer_funds", source: "http", arguments: "{}", argsTruncated: false }],
      },
    });
    await user.click(screen.getByTestId("approve-button"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled());
    expect(resumes).toEqual([]);
  });
});

describe("per-row pending state", () => {
  it("keeps a row disabled while its own decision is in flight, even after another row is decided", async () => {
    let releaseFirst: () => void = () => {};
    const firstHeld = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const resumes: string[] = [];
    server.use(
      http.post("*/agents/:conversationId/resume", async ({ params }) => {
        resumes.push(params.conversationId as string);
        if (params.conversationId === "conv-rule-1") await firstHeld;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    serveInbox([rulePause, secondRulePause]);
    for (const item of [rulePause, secondRulePause]) {
      serveStatus(item.conversationId, {
        pausedAt: item.pausedAt,
        pauseDetails: { type: "RULE", reason: null, actions: [] },
      });
    }
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    await approveRow(user, "conv-rule-1");
    await waitFor(() => expect(resumes).toEqual(["conv-rule-1"]));
    expect(screen.getByTestId("approve-conv-rule-1")).toBeDisabled();

    await approveRow(user, "conv-rule-2");
    await waitFor(() => expect(resumes).toEqual(["conv-rule-1", "conv-rule-2"]));
    // The second call used to overwrite the mutation's variables, re-enabling
    // row 1 while its resume was still running.
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId("approve-conv-rule-1")).toBeDisabled();

    releaseFirst();
    // Both outcomes are reported — `mutate` callbacks fired for the latest call only.
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledTimes(2));
  });
});
