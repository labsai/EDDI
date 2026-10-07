import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { PauseNotice } from "@/components/hitl/pause-notice";
import { getApprovalStatus, type ApprovalStatusSummary } from "@/lib/api/hitl";

vi.mock("@/lib/api/hitl", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/hitl")>()),
  getApprovalStatus: vi.fn(),
}));

const PAUSED_AT = "2026-08-03T10:00:00.000Z";
const mockStatus = vi.mocked(getApprovalStatus);

const notYetStored: ApprovalStatusSummary = {
  conversationId: "c1",
  state: "READY",
  pausedAt: "",
  pauseId: "",
  pauseDetails: null,
};
const stored: ApprovalStatusSummary = {
  conversationId: "c1",
  state: "AWAITING_HUMAN",
  pausedAt: PAUSED_AT,
  pauseId: String(Date.parse(PAUSED_AT)),
  pauseReason: "Tool approval required",
  pauseDetails: {
    type: "TOOL_CALL",
    calls: [{ callId: "x", toolName: "setupAgent", source: "http", arguments: "{}", argsTruncated: false, gateReason: "g", requestPinned: false }],
    executedUngatedCalls: [],
    outcomeUnknown: [],
  },
};
const decided: ApprovalStatusSummary = { ...notYetStored, state: "IN_PROGRESS" };

const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

function mount(onResolved = vi.fn()) {
  renderWithProviders(<PauseNotice conversationId="c1" pausedAt={PAUSED_AT} onResolved={onResolved} />);
  return onResolved;
}

describe("PauseNotice (read-only pause surface)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockStatus.mockReset();
  });
  afterEach(() => vi.useRealTimers());

  it("says it is saving the pause on a not-yet-stored read, then names what is pending", async () => {
    mockStatus.mockResolvedValueOnce(notYetStored).mockResolvedValue(stored);
    mount();
    await advance(0);
    expect(screen.getByTestId("pause-notice-pending")).toBeInTheDocument();
    expect(screen.queryByTestId("pause-notice-tools")).not.toBeInTheDocument();
    await advance(600);
    expect(screen.getByTestId("pause-notice-tools")).toHaveTextContent("setupAgent");
    expect(screen.queryByTestId("pause-notice-pending")).not.toBeInTheDocument();
    expect(screen.getByTestId("pause-notice-review").getAttribute("href")).toBe(
      "/manage/conversationview/c1?awaiting=1",
    );
  });

  it("does NOT report a first not-yet-stored read as resolved", async () => {
    mockStatus.mockResolvedValueOnce(notYetStored).mockResolvedValue(stored);
    const onResolved = mount();
    await advance(0);
    await advance(600);
    expect(onResolved).not.toHaveBeenCalled();
  });

  it("reports resolved once a pause that was seen stored is decided elsewhere", async () => {
    mockStatus.mockResolvedValueOnce(stored).mockResolvedValue(decided);
    const onResolved = mount();
    await advance(0);
    expect(onResolved).not.toHaveBeenCalled();
    await advance(5100); // the watch re-reads every 5 s
    expect(onResolved).toHaveBeenCalledTimes(1);
    await advance(20_000);
    expect(onResolved).toHaveBeenCalledTimes(1);
  });

  it("reports resolved when the window runs out on a settled read (decided before the first read)", async () => {
    mockStatus.mockResolvedValue(notYetStored);
    const onResolved = mount();
    await advance(9_000);
    expect(onResolved).not.toHaveBeenCalled();
    await advance(2_000);
    expect(onResolved).toHaveBeenCalled();
  });
});
