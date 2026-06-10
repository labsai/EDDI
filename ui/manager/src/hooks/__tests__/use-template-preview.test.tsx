import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { useTemplatePreview } from "@/hooks/use-template-preview";
import { http, HttpResponse } from "msw";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useTemplatePreview", () => {
  it("previews a template successfully", async () => {
    server.use(
      http.post("*/administration/preview/template", () => {
        return HttpResponse.json({
          resolved: "Hello Jane",
          availableVariables: ["properties.userName"],
          variableValues: { "properties.userName": "Jane" },
          error: null,
        });
      }),
    );

    const { result } = renderHook(() => useTemplatePreview(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      template: "Hello {properties.userName}",
      conversationId: "conv-123",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("resolved");
    expect(result.current.data!.resolved).toBe("Hello Jane");
    expect(result.current.data).toHaveProperty("availableVariables");
  });

  it("handles template preview error response", async () => {
    server.use(
      http.post("*/administration/preview/template", () => {
        return HttpResponse.json({
          resolved: null,
          availableVariables: [],
          variableValues: {},
          error: "Template syntax error",
        });
      }),
    );

    const { result } = renderHook(() => useTemplatePreview(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      template: "Hello {invalid",
      conversationId: "conv-123",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.resolved).toBeNull();
    expect(result.current.data!.error).toBe("Template syntax error");
  });
});
