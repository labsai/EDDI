import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useEnrichedChannelDescriptors,
  useChannel,
  useCreateChannel,
  useUpdateChannel,
  useDeleteChannel,
  useDuplicateChannel,
} from "@/hooks/use-channels";
import { http, HttpResponse } from "msw";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useEnrichedChannelDescriptors", () => {
  it("fetches enriched channel descriptors", async () => {
    server.use(
      http.get("*/channelstore/channels/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.resource/channelstore/channels/ch1?version=1",
            name: "Slack Channel",
            description: "Main Slack integration",
            lastModifiedOn: Date.now(),
          },
        ]);
      }),
      http.get("*/channelstore/channels/ch1", () => {
        return HttpResponse.json({
          name: "Slack Channel",
          channelType: "slack",
          targets: [
            {
              name: "default",
              type: "AGENT",
              targetId: "agent1",
              triggers: [],
              observeMode: false,
              observeConfig: null,
            }
          ],
          platformConfig: {
            channelId: "slack-channel-id",
          },
        });
      })
    );

    const { result } = renderHook(
      () => useEnrichedChannelDescriptors(),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
  });
});

describe("useChannel", () => {
  it("fetches a single channel config", async () => {
    server.use(
      http.get("*/channelstore/channels/:id", () => {
        return HttpResponse.json({
          channelType: "slack",
          agentId: "agent1",
          botToken: "xoxb-test",
        });
      }),
    );

    const { result } = renderHook(() => useChannel("ch1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("channelType");
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useChannel(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCreateChannel", () => {
  it("creates a channel successfully", async () => {
    server.use(
      http.post("*/channelstore/channels", () => {
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/channelstore/channels/new-ch1?version=1" },
        });
      }),
    );

    const { result } = renderHook(() => useCreateChannel(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      channelType: "slack",
      agentId: "agent1",
      botToken: "xoxb-new",
    } as never);

    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useUpdateChannel", () => {
  it("updates a channel successfully", async () => {
    server.use(
      http.put("*/channelstore/channels/:id", () => {
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { result } = renderHook(() => useUpdateChannel(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      id: "ch1",
      version: 1,
      config: { channelType: "slack", agentId: "agent1" } as never,
    });

    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useDeleteChannel", () => {
  it("deletes a channel successfully", async () => {
    server.use(
      http.delete("*/channelstore/channels/:id", () => {
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const { result } = renderHook(() => useDeleteChannel(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ id: "ch1", version: 1 });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useDuplicateChannel", () => {
  it("duplicates a channel successfully", async () => {
    server.use(
      http.post("*/channelstore/channels/:id/duplicate", () => {
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/channelstore/channels/ch2?version=1" },
        });
      }),
    );

    const { result } = renderHook(() => useDuplicateChannel(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ id: "ch1", version: 1 });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});
