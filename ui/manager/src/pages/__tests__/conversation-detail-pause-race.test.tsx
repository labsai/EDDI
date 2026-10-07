import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse, delay } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { server } from "@/test/mocks/server";

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() } }));

/**
 * The stream announces a pause before it is stored, and this page reads the
 * store. Same rule as every approval surface: nothing is approvable until
 * approval-status describes THIS pause, and a decision collapses into a receipt.
 */

const PAUSED_AT = "2026-07-01T10:00:00.000Z";
const PAUSE_ID = String(Date.parse(PAUSED_AT));

const conversation = (state: string) => ({
  agentId: "agent1",
  agentVersion: 1,
  conversationId: "conv-paused",
  conversationState: state,
  ...(state === "AWAITING_HUMAN" ? { hitlPausedAt: PAUSED_AT, hitlPauseType: "TOOL_CALL" } : {}),
  environment: "production",
  conversationSteps: [],
  conversationOutputs: [],
  conversationProperties: {},
});

const notYetStored = {
  conversationId: "conv-paused",
  state: "READY",
  pausedAt: "",
  pauseReason: "",
  pauseId: "",
  pauseDetails: null,
};

const stored = {
  conversationId: "conv-paused",
  state: "AWAITING_HUMAN",
  pausedAt: PAUSED_AT,
  pauseId: PAUSE_ID,
  pauseReason: "Tool approval required",
  pauseDetails: {
    type: "TOOL_CALL",
    calls: [
      { callId: "call-1", toolName: "setupAgent", source: "http", arguments: "{}", argsTruncated: false, gateReason: "g", requestPinned: false },
    ],
    executedUngatedCalls: [],
    outcomeUnknown: [],
  },
};

function render(query = "") {
  return renderPage(
    `/manage/conversationview/conv-paused${query}`,
    <ConversationDetailPage />,
    "/manage/conversationview/:id",
  );
}

describe("conversation-detail while the stored pause catches up", () => {
  it("holds Approve on a not-yet-stored read, then offers the tool call; a decision collapses into a receipt", async () => {
    let reads = 0;
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => HttpResponse.json(conversation("AWAITING_HUMAN"))),
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json([notYetStored, notYetStored, stored][Math.min(reads++, 2)]),
      ),
      http.post("*/agents/:conversationId/resume", async () => {
        await delay(400);
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const user = userEvent.setup();
    render();

    expect(await screen.findByTestId("approval-details-pending")).toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeDisabled();

    await waitFor(() => expect(screen.getByTestId("tool-call-call-1")).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByTestId("approve-button")).toBeEnabled();

    await user.click(screen.getByTestId("approve-button"));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve" }));
    const summary = await screen.findByTestId("approval-decision-summary");
    expect(summary).toHaveAttribute("data-verdict", "APPROVED");
    expect(screen.getByTestId("approval-decision-summary-call-call-1")).toHaveTextContent("setupAgent");
  });

  it("re-reads for a few seconds when opened from a pause link before the pause is stored", async () => {
    let reads = 0;
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () =>
        HttpResponse.json(conversation(reads++ < 1 ? "READY" : "AWAITING_HUMAN")),
      ),
      http.get("*/agents/:conversationId/approval-status", () => HttpResponse.json(stored)),
    );
    render("?awaiting=1");

    expect(await screen.findByTestId("awaiting-pause-notice")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("approval-banner")).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.queryByTestId("awaiting-pause-notice")).not.toBeInTheDocument();
  });

  it("shows no waiting notice without the pause-link hint", async () => {
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => HttpResponse.json(conversation("READY"))),
    );
    render();
    await screen.findByTestId("conversation-chat");
    expect(screen.queryByTestId("awaiting-pause-notice")).not.toBeInTheDocument();
  });

  it("hands the receipt over to the settled state promptly after a successful resume", async () => {
    let resumed = false;
    let reads = 0;
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => {
        reads++;
        return HttpResponse.json(conversation(resumed ? "READY" : "AWAITING_HUMAN"));
      }),
      http.get("*/agents/:conversationId/approval-status", () => HttpResponse.json(stored)),
      http.post("*/agents/:conversationId/resume", () => {
        resumed = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const user = userEvent.setup();
    render();

    await waitFor(() => expect(screen.getByTestId("approve-button")).toBeEnabled());
    const readsBefore = reads;
    await user.click(screen.getByTestId("approve-button"));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve" }));

    // The conversation is re-read straight away, and the card (with its receipt) gives way.
    await waitFor(() => expect(reads).toBeGreaterThan(readsBefore), { timeout: 2000 });
    await waitFor(() => expect(screen.queryByTestId("approval-banner")).not.toBeInTheDocument(), { timeout: 2000 });
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
  });
});
