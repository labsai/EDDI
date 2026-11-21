import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@eddi/ui";
import { LogsPage } from "@/pages/logs";
import { useLogStream } from "@/hooks/use-logs";

vi.mock("@/hooks/use-logs", () => ({
  useLogStream: vi.fn(),
  useHistoryLogs: vi.fn().mockReturnValue({ data: [], isLoading: false, refetch: vi.fn() }),
  useInstanceId: vi.fn().mockReturnValue({ data: { instanceId: "i" } }),
}));
vi.mock("@eddi/app-kit/hooks/use-chat", () => ({
  useDeployedAgents: vi.fn().mockReturnValue({ data: [] }),
}));
vi.mock("@/hooks/use-onboarding", () => ({ useOnboarding: vi.fn().mockReturnValue(vi.fn()) }));
vi.mock("@eddi/app-kit/lib/api/conversations", () => ({
  getConversationDescriptors: vi.fn().mockResolvedValue([]),
  parseConversationUri: vi.fn((uri: string) => uri.split("/").pop() ?? ""),
}));

/** The live view's cap: a full view never changes length again. */
const CAP = 500;

/** `count` entries, newest first, the newest one stamped `newest`. */
function entriesEndingAt(newest: number, count = CAP) {
  return Array.from({ length: count }, (_, i) => ({
    timestamp: 1_700_000_000_000 + (newest - i) * 1000,
    level: "INFO",
    message: `line ${newest - i}`,
    loggerName: "l",
  }));
}

function stream(entries: ReturnType<typeof entriesEndingAt>) {
  return {
    entries,
    sseConnected: true,
    exhausted: false,
    reconnect: vi.fn(),
    seeded: true,
    paused: false,
    setPaused: vi.fn(),
    clearEntries: vi.fn(),
  };
}

function tree(client: QueryClient) {
  return (
    <MemoryRouter initialEntries={["/manage/logs"]}>
      <QueryClientProvider client={client}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <LogsPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("LogsPage live tail", () => {
  it("keeps following the newest line once the view is full (length no longer changes)", () => {
    const client = new QueryClient();
    vi.mocked(useLogStream).mockReturnValue(stream(entriesEndingAt(1000)));
    const { rerender } = render(tree(client));

    const scroller = screen.getByTestId("live-log-scroll");
    const writes: number[] = [];
    Object.defineProperty(scroller, "scrollHeight", { value: 99_000, configurable: true });
    Object.defineProperty(scroller, "scrollTop", {
      configurable: true,
      get: () => 0,
      set: (v: number) => writes.push(v),
    });

    // One more line arrives: the oldest drops out, the count stays at the cap.
    vi.mocked(useLogStream).mockReturnValue(stream(entriesEndingAt(1001)));
    rerender(tree(client));

    expect(writes).toContain(99_000);
  });

  it("offers the New logs button once the user has scrolled away, at the 500-entry cap", () => {
    const client = new QueryClient();
    vi.mocked(useLogStream).mockReturnValue(stream(entriesEndingAt(1000)));
    render(tree(client));
    const scroller = screen.getByTestId("live-log-scroll");
    Object.defineProperty(scroller, "scrollHeight", { value: 99_000, configurable: true });
    Object.defineProperty(scroller, "clientHeight", { value: 500, configurable: true });
    scroller.scrollTop = 0;
    fireEvent.scroll(scroller);
    expect(screen.getByTestId("scroll-to-bottom")).toBeInTheDocument();
  });
});
