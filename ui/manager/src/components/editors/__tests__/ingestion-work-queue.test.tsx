import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { WorkQueueStatus } from "@/components/editors/ingestion-run-history";
import type { SourceWork } from "@/lib/api/ingestion-sources";

const work = (overrides: Partial<SourceWork> = {}): SourceWork => ({
  sourceId: "src-1",
  queued: 0,
  deferred: 0,
  inFlight: 0,
  done: 0,
  failed: 0,
  nextAttemptAt: null,
  ...overrides,
});

describe("WorkQueueStatus", () => {
  it("shows nothing for a source that never queued anything, or an older server", () => {
    const { rerender } = renderWithProviders(
      <WorkQueueStatus
        work={work()}
        canRetry
        onRetryFailed={() => {}}
        retrying={false}
        retryError={false}
        testId="s"
      />,
    );
    expect(screen.queryByTestId("s-work")).not.toBeInTheDocument();
    rerender(
      <WorkQueueStatus
        work={undefined}
        canRetry
        onRetryFailed={() => {}}
        retrying={false}
        retryError={false}
        testId="s"
      />,
    );
    expect(screen.queryByTestId("s-work")).not.toBeInTheDocument();
  });

  it("shows the counts by state and when deferred work is due", () => {
    renderWithProviders(
      <WorkQueueStatus
        work={work({
          queued: 3,
          deferred: 2,
          inFlight: 1,
          done: 40,
          nextAttemptAt: "2099-01-01T10:00:00Z",
        })}
        canRetry
        onRetryFailed={() => {}}
        retrying={false}
        retryError={false}
        testId="s"
      />,
    );
    expect(screen.getByTestId("s-work-queued")).toHaveTextContent("3 queued");
    expect(screen.getByTestId("s-work-deferred")).toHaveTextContent(
      "2 waiting",
    );
    expect(screen.getByTestId("s-work-inFlight")).toHaveTextContent(
      "1 in progress",
    );
    expect(screen.getByTestId("s-work-done")).toHaveTextContent("40 done");
    expect(screen.getByTestId("s-work-next")).toBeInTheDocument();
    expect(screen.queryByTestId("s-work-retry")).not.toBeInTheDocument();
  });

  it("offers Retry failed when something failed for good, and only to an editor", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithProviders(
      <WorkQueueStatus
        work={work({ failed: 2 })}
        canRetry
        onRetryFailed={onRetry}
        retrying={false}
        retryError={false}
        testId="s"
      />,
    );
    await user.click(screen.getByTestId("s-work-retry"));
    expect(onRetry).toHaveBeenCalledOnce();

    rerender(
      <WorkQueueStatus
        work={work({ failed: 2 })}
        canRetry={false}
        onRetryFailed={onRetry}
        retrying={false}
        retryError={false}
        testId="s"
      />,
    );
    expect(screen.queryByTestId("s-work-retry")).not.toBeInTheDocument();
  });
});
