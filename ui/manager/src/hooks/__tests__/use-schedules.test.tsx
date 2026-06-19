import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  useSchedules,
  useSchedule,
  useCreateSchedule,
  useUpdateSchedule,
  useDeleteSchedule,
  useToggleSchedule,
  useFireNow,
  useFireLogs,
  useFailedFires,
  useRetryDeadLetter,
} from "@/hooks/use-schedules";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            {children}
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );
  };
}

describe("useSchedules", () => {
  it("fetches all schedules", async () => {
    const { result } = renderHook(() => useSchedules(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("name");
    expect(result.current.data![0]).toHaveProperty("triggerType");
  });

  it("handles API error", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () => {
        return HttpResponse.json({}, { status: 500 });
      })
    );
    const { result } = renderHook(() => useSchedules(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useSchedule", () => {
  it("fetches a single schedule", async () => {
    const { result } = renderHook(() => useSchedule("sched-1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.name).toBe("Daily Health Check");
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useSchedule(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCreateSchedule", () => {
  it("creates a schedule", async () => {
    const { result } = renderHook(() => useCreateSchedule(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        name: "Test Schedule",
        triggerType: "CRON",
        agentId: "agent1",
        cronExpression: "0 */2 * * *",
      } as never);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUpdateSchedule", () => {
  it("updates a schedule", async () => {
    const { result } = renderHook(() => useUpdateSchedule(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        id: "sched-1",
        config: { name: "Updated" } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteSchedule", () => {
  it("deletes a schedule", async () => {
    const { result } = renderHook(() => useDeleteSchedule(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("sched-1");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useToggleSchedule", () => {
  it("toggles a schedule", async () => {
    const { result } = renderHook(() => useToggleSchedule(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "sched-1", enable: true });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useFireNow", () => {
  it("fires a schedule manually", async () => {
    const { result } = renderHook(() => useFireNow(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("sched-1");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useFireLogs", () => {
  it("fetches fire logs for a schedule", async () => {
    const { result } = renderHook(() => useFireLogs("sched-1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when not enabled", () => {
    const { result } = renderHook(() => useFireLogs("sched-1", false), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useFailedFires", () => {
  it("fetches failed firings", async () => {
    const { result } = renderHook(() => useFailedFires(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useRetryDeadLetter", () => {
  it("retries a dead letter", async () => {
    const { result } = renderHook(() => useRetryDeadLetter(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("fire-fail-1");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
