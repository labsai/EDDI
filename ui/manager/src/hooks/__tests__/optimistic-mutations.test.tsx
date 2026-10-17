import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse, delay } from "msw";
import { server } from "@/test/mocks/server";
import { useToggleSchedule } from "@/hooks/use-schedules";
import { useSubmitHumanInput } from "@/hooks/use-hitl";
import type { GroupConversation } from "@/lib/api/groups";
import type { ScheduleConfiguration } from "@/lib/api/schedules";
import { agentDeploymentQueries, agentKeys } from "@/lib/query-keys";

/**
 * A click should change what the user sees at once — not after the request and
 * the refetch behind it. These pin the optimistic paths added in the 2026-10
 * responsiveness review.
 */
function setup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { queryClient, wrapper };
}

const LIST_KEY = ["schedules", "list", undefined];
const schedule = (id: string, enabled: boolean) => ({ id, name: id, enabled }) as ScheduleConfiguration;

describe("useToggleSchedule", () => {
  it("flips the cached row before the server answers", async () => {
    let release!: () => void;
    const answered = new Promise<void>((r) => (release = r));
    server.use(
      http.post("*/schedulestore/schedules/:id/enable", async () => {
        await answered;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const { queryClient, wrapper } = setup();
    queryClient.setQueryData(LIST_KEY, [schedule("a", false), schedule("b", false)]);
    const { result } = renderHook(() => useToggleSchedule(), { wrapper });

    act(() => result.current.mutate({ id: "a", enable: true }));

    await waitFor(() =>
      expect(queryClient.getQueryData<ScheduleConfiguration[]>(LIST_KEY)?.map((s) => s.enabled)).toEqual([
        true,
        false,
      ]),
    );
    expect(result.current.isPending).toBe(true);
    release();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("puts the row back when the request fails", async () => {
    server.use(
      http.post("*/schedulestore/schedules/:id/enable", async () => {
        await delay(10);
        return HttpResponse.json({ message: "nope" }, { status: 500 });
      }),
    );
    const { queryClient, wrapper } = setup();
    queryClient.setQueryData(LIST_KEY, [schedule("a", false)]);
    // Keep the rollback observable: the settle-time refetch would otherwise
    // replace the cache with whatever the mock list returns.
    queryClient.setQueryDefaults(["schedules"], { queryFn: () => new Promise(() => {}) });
    const { result } = renderHook(() => useToggleSchedule(), { wrapper });

    act(() => result.current.mutate({ id: "a", enable: true }));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(queryClient.getQueryData<ScheduleConfiguration[]>(LIST_KEY)?.[0]?.enabled).toBe(false);
  });
});

describe("agentDeploymentQueries", () => {
  const matches = (key: readonly unknown[], ids = ["agent-1"]) =>
    agentDeploymentQueries(ids).predicate({ queryKey: key });

  it("refreshes the deployed agent's own entries and the environment listings", () => {
    expect(matches([...agentKeys.all, "deployment", "production", "agent-1", 2])).toBe(true);
    expect(matches([...agentKeys.all, "deploymentStatuses", "agent-1", 2])).toBe(true);
    expect(matches(agentKeys.deploymentImpact("production", "agent-1", 2))).toBe(true);
    expect(matches([...agentKeys.all, "deploymentListing", "production"])).toBe(true);
    expect(matches(["agents", "profile", "agent-1", "production"])).toBe(true);
  });

  it("leaves other agents' cards and the descriptor lists alone", () => {
    expect(matches([...agentKeys.all, "deploymentStatuses", "agent-2", 1])).toBe(false);
    expect(matches([...agentKeys.all, "deployment", "production", "agent-2", 1])).toBe(false);
    expect(matches(agentKeys.descriptorsInfinite(""))).toBe(false);
    expect(matches(agentKeys.descriptors(20, 0, ""))).toBe(false);
  });

  it("refreshes a sub-key it does not know, to stay on the safe side", () => {
    expect(matches([...agentKeys.all, "something-new"])).toBe(true);
  });
});

describe("useSubmitHumanInput", () => {
  const KEY = ["groupConversations", "grp-1", "gc-1"];
  const awaiting = {
    id: "gc-1",
    state: "AWAITING_HUMAN_INPUT",
    pendingHumanInput: { memberId: "human-1" },
  } as unknown as GroupConversation;
  const vars = { groupId: "grp-1", gcId: "gc-1", memberId: "human-1", content: "my turn" };

  it("hands the turn over in the cache before the server answers", async () => {
    let release!: () => void;
    const answered = new Promise<void>((r) => (release = r));
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/human-input", async () => {
        await answered;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const { queryClient, wrapper } = setup();
    queryClient.setQueryData(KEY, awaiting);
    const { result } = renderHook(() => useSubmitHumanInput(), { wrapper });

    act(() => result.current.mutate(vars));

    await waitFor(() => expect(queryClient.getQueryData<GroupConversation>(KEY)?.state).toBe("IN_PROGRESS"));
    expect(queryClient.getQueryData<GroupConversation>(KEY)?.pendingHumanInput).toBeNull();
    release();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("gives the turn back when the submit fails", async () => {
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/human-input", async () => {
        await delay(10);
        return HttpResponse.json({ message: "nope" }, { status: 500 });
      }),
    );
    const { queryClient, wrapper } = setup();
    queryClient.setQueryData(KEY, awaiting);
    queryClient.setQueryDefaults(["groupConversations"], { queryFn: () => new Promise(() => {}) });
    const { result } = renderHook(() => useSubmitHumanInput(), { wrapper });

    act(() => result.current.mutate(vars));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(queryClient.getQueryData<GroupConversation>(KEY)?.state).toBe("AWAITING_HUMAN_INPUT");
  });
});
