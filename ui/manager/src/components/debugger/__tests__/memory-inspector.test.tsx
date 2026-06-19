import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { MemoryInspector } from "@/components/debugger/memory-inspector";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

const DETAILED_URL = "*/agents/:id";

describe("MemoryInspector", () => {
  it("shows empty state when no conversationId", () => {
    renderWithProviders(<MemoryInspector conversationId={null} />);
    expect(
      screen.getByText("Start a conversation to inspect memory")
    ).toBeInTheDocument();
  });

  it("shows loading spinner when fetching", () => {
    // Delay the response indefinitely
    server.use(
      http.get(DETAILED_URL, () => {
        return new Promise(() => {});
      })
    );
    const { container } = renderWithProviders(
      <MemoryInspector conversationId="conv-123" />
    );
    const spinner = container.querySelector(".animate-spin");
    expect(spinner).not.toBeNull();
  });

  it("shows error state when API fails", async () => {
    server.use(
      http.get(DETAILED_URL, () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    renderWithProviders(<MemoryInspector conversationId="conv-error" />);
    await waitFor(() => {
      expect(screen.getByTestId("memory-inspector-error")).toBeInTheDocument();
    });
    expect(screen.getByText("Failed to load memory data")).toBeInTheDocument();
  });

  it("shows memory data when loaded", async () => {
    server.use(
      http.get(DETAILED_URL, ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("returnDetailed") === "true") {
          return HttpResponse.json({
            conversationSteps: [
              {
                conversationStep: [
                  {
                    key: "input:initial",
                    value: "Hello",
                    timestamp: Date.now(),
                    originWorkflowId: null,
                  },
                ],
              },
            ],
            conversationProperties: {},
          });
        }
        return new HttpResponse(null, { status: 404 });
      })
    );
    renderWithProviders(<MemoryInspector conversationId="conv-data" />);
    await waitFor(() => {
      expect(screen.getByTestId("memory-inspector")).toBeInTheDocument();
    });
    expect(screen.getByText("Conversation Memory")).toBeInTheDocument();
    expect(screen.getByText(/Step 1/)).toBeInTheDocument();
  });

  it("shows empty steps message when no steps", async () => {
    server.use(
      http.get(DETAILED_URL, ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("returnDetailed") === "true") {
          return HttpResponse.json({
            conversationSteps: [],
            conversationProperties: {},
          });
        }
        return new HttpResponse(null, { status: 404 });
      })
    );
    renderWithProviders(<MemoryInspector conversationId="conv-empty" />);
    await waitFor(() => {
      expect(screen.getByTestId("memory-inspector")).toBeInTheDocument();
    });
    expect(screen.getByText("No memory data available")).toBeInTheDocument();
  });

  it("has a refresh button", async () => {
    server.use(
      http.get(DETAILED_URL, ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("returnDetailed") === "true") {
          return HttpResponse.json({
            conversationSteps: [],
            conversationProperties: {},
          });
        }
        return new HttpResponse(null, { status: 404 });
      })
    );
    renderWithProviders(<MemoryInspector conversationId="conv-btn" />);
    await waitFor(() => {
      expect(screen.getByTestId("memory-inspector")).toBeInTheDocument();
    });
    expect(screen.getByTestId("memory-refresh")).toBeInTheDocument();
  });

  it("shows properties when available", async () => {
    server.use(
      http.get(DETAILED_URL, ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("returnDetailed") === "true") {
          return HttpResponse.json({
            conversationSteps: [],
            conversationProperties: {
              userName: "Alice",
              language: "en",
            },
          });
        }
        return new HttpResponse(null, { status: 404 });
      })
    );
    renderWithProviders(<MemoryInspector conversationId="conv-props" />);
    await waitFor(() => {
      expect(screen.getByTestId("memory-inspector")).toBeInTheDocument();
    });
    expect(screen.getByText("Properties")).toBeInTheDocument();
  });

  it("shows grouped items by originWorkflowId", async () => {
    server.use(
      http.get(DETAILED_URL, ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("returnDetailed") === "true") {
          return HttpResponse.json({
            conversationSteps: [
              {
                conversationStep: [
                  {
                    key: "input:initial",
                    value: "Hello",
                    timestamp: Date.now(),
                    originWorkflowId: null,
                  },
                  {
                    key: "output:text",
                    value: "Hi there!",
                    timestamp: Date.now(),
                    originWorkflowId: "wf1",
                  },
                ],
              },
            ],
            conversationProperties: {},
          });
        }
        return new HttpResponse(null, { status: 404 });
      })
    );
    renderWithProviders(<MemoryInspector conversationId="conv-groups" />);
    await waitFor(() => {
      expect(screen.getByTestId("memory-inspector")).toBeInTheDocument();
    });
    // Step 1 is expanded by default, shows groups
    expect(screen.getByText("Input")).toBeInTheDocument();
    expect(screen.getByText("wf1")).toBeInTheDocument();
    expect(screen.getByText("input:initial")).toBeInTheDocument();
    expect(screen.getByText("output:text")).toBeInTheDocument();
  });
});
