import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import { useApprovalStatus } from "@/hooks/use-hitl";
import { getApprovalStatus, type ApprovalStatusSummary } from "@/lib/api/hitl";

vi.mock("@/lib/api/hitl", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/hitl")>()),
  getApprovalStatus: vi.fn(),
}));

/**
 * The race behind the blind-approve card: the chat stream's `done` announces a
 * pause BEFORE the paused memory is stored, and approval-status reads the store.
 * The first read can therefore say "not paused" — and a card built from it has
 * no calls on it to object to.
 */

const PAUSED_AT = "2026-08-03T10:00:00.000Z";
const PAUSE_ID = String(Date.parse(PAUSED_AT));

const notYetStored: ApprovalStatusSummary = {
  conversationId: "conv-1",
  state: "READY",
  pausedAt: "",
  pauseReason: "",
  pauseId: "",
  pauseDetails: null,
};

const toolPause = (over: Partial<ApprovalStatusSummary> = {}): ApprovalStatusSummary => ({
  conversationId: "conv-1",
  state: "AWAITING_HUMAN",
  pausedAt: PAUSED_AT,
  pauseId: PAUSE_ID,
  pauseReason: "Tool approval required",
  pauseDetails: {
    type: "TOOL_CALL",
    calls: [
      {
        callId: "call-1",
        toolName: "setupAgent",
        source: "http",
        arguments: JSON.stringify({ requestBody: JSON.stringify({ agentName: "Support" }) }),
        argsTruncated: false,
        gateReason: "http.post:*",
        requestPinned: false,
      },
    ],
    executedUngatedCalls: [],
    outcomeUnknown: [],
  },
  ...over,
});

/** The wiring operator.tsx does, minus the page around it. */
function Card({ expected = PAUSED_AT }: { expected?: string | null }) {
  const status = useApprovalStatus("conv-1", true, expected, { verifyPause: true });
  return (
    <ApprovalBanner
      surface="regular"
      pauseDetails={status.data?.pauseDetails ?? null}
      pauseDetailsPending={status.detailsPending}
      pauseDetailsError={status.detailsError}
      onRetryPauseDetails={() => void status.retry()}
      requireExplicitPerCall
      expectToolCall
      onDecide={vi.fn()}
    />
  );
}

const mockStatus = vi.mocked(getApprovalStatus);

/** Let timers and the promises they trigger run. */
const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

describe("approval card while the stored pause catches up", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockStatus.mockReset();
  });
  afterEach(() => vi.useRealTimers());

  it("shows pending and a disabled Approve for a not-yet-stored read, then the tool layout once it lands", async () => {
    mockStatus.mockResolvedValueOnce(notYetStored).mockResolvedValue(toolPause());
    renderWithProviders(<Card />);
    await advance(0);

    // First response: not paused yet. NOT the RULE layout with Approve live.
    expect(mockStatus).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("approval-details-pending")).toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeDisabled();

    await advance(600);

    expect(screen.getByTestId("approval-banner")).toHaveAttribute("data-pause-type", "TOOL_CALL");
    expect(screen.getByTestId("tool-call-call-1")).toBeInTheDocument();
    expect(screen.queryByTestId("approval-details-pending")).not.toBeInTheDocument();
    // A lone call needs no per-call verdict: Approve is available once the details match.
    expect(screen.queryByTestId("tool-approve-call-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeEnabled();

    // Matching data stops the polling.
    const calls = mockStatus.mock.calls.length;
    await advance(5000);
    expect(mockStatus).toHaveBeenCalledTimes(calls);
  });

  it("backs off while polling (500 ms, then slower)", async () => {
    mockStatus.mockResolvedValue(notYetStored);
    renderWithProviders(<Card />);
    await advance(0);
    expect(mockStatus).toHaveBeenCalledTimes(1);
    await advance(500);
    expect(mockStatus).toHaveBeenCalledTimes(2);
    await advance(999);
    expect(mockStatus).toHaveBeenCalledTimes(2);
    await advance(1);
    expect(mockStatus).toHaveBeenCalledTimes(3);
  });

  it("treats AWAITING_HUMAN with another pause's id as pending, never approvable", async () => {
    mockStatus.mockResolvedValue(toolPause({ pauseId: String(Date.parse(PAUSED_AT) - 60_000) }));
    renderWithProviders(<Card />);
    await advance(0);

    expect(screen.getByTestId("approval-details-pending")).toBeInTheDocument();
    // Its calls belong to the previous pause: they are not rendered at all.
    expect(screen.queryByTestId("tool-call-call-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeDisabled();
  });

  it("becomes an error with Retry after ~10 s of non-matching responses, Approve disabled throughout", async () => {
    mockStatus.mockResolvedValue(notYetStored);
    renderWithProviders(<Card />);
    await advance(9000);
    expect(screen.getByTestId("approval-details-pending")).toBeInTheDocument();
    expect(screen.queryByTestId("approval-details-error")).not.toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeDisabled();

    await advance(1500);
    expect(screen.getByTestId("approval-details-error")).toBeInTheDocument();
    expect(screen.getByTestId("approval-details-retry")).toBeInTheDocument();
    expect(screen.queryByTestId("approval-details-pending")).not.toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeDisabled();

    // Polling has stopped: no endless spinner, no endless traffic.
    const calls = mockStatus.mock.calls.length;
    await advance(10_000);
    expect(mockStatus).toHaveBeenCalledTimes(calls);
  });

  it("restarts the window on Retry, and a matching response then renders the tool layout", async () => {
    mockStatus.mockResolvedValue(notYetStored);
    renderWithProviders(<Card />);
    await advance(11_000);
    expect(screen.getByTestId("approval-details-error")).toBeInTheDocument();

    mockStatus.mockResolvedValue(toolPause());
    fireEvent.click(screen.getByTestId("approval-details-retry"));
    await advance(0);

    expect(screen.queryByTestId("approval-details-error")).not.toBeInTheDocument();
    expect(screen.getByTestId("approval-banner")).toHaveAttribute("data-pause-type", "TOOL_CALL");
    expect(screen.getByTestId("tool-call-call-1")).toBeInTheDocument();
  });

  it("gives Retry a full window of its own when the retry still does not match", async () => {
    mockStatus.mockResolvedValue(notYetStored);
    renderWithProviders(<Card />);
    await advance(11_000);
    fireEvent.click(screen.getByTestId("approval-details-retry"));
    await advance(0);

    expect(screen.getByTestId("approval-details-pending")).toBeInTheDocument();
    await advance(8000);
    expect(screen.getByTestId("approval-details-pending")).toBeInTheDocument();
    await advance(3000);
    expect(screen.getByTestId("approval-details-error")).toBeInTheDocument();
  });

  it("falls back to pausedAt for a backend that predates pauseId", async () => {
    const legacy = toolPause();
    delete legacy.pauseId;
    mockStatus.mockResolvedValue(legacy);
    renderWithProviders(<Card />);
    await advance(0);
    expect(screen.getByTestId("tool-call-call-1")).toBeInTheDocument();
  });

  it("is an error, not an endless spinner, when the read itself fails", async () => {
    mockStatus.mockRejectedValue(new Error("boom"));
    renderWithProviders(<Card />);
    await advance(0);
    expect(screen.getByTestId("approval-details-error")).toBeInTheDocument();
    expect(screen.getByTestId("approve-button")).toBeDisabled();
  });
});
