import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PromptViewer } from "@/components/debugger/prompt-viewer";

function renderViewer(conversationId: string | null = "conv-1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-prompt">
          <PromptViewer conversationId={conversationId} />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("PromptViewer", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("renders empty state when no conversationId", () => {
    renderViewer(null);
    expect(screen.getByText(/Start a conversation to inspect prompts/i)).toBeInTheDocument();
  });

  it("renders prompt-viewer testid when LLM data is found", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        expect(screen.getByTestId("prompt-viewer")).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays step index number", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        expect(screen.getByText(/Step/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays task type badge", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        // Audit entries have taskType "langchain"
        expect(screen.getByText(/langchain/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays cost in the metrics strip", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        expect(screen.getByText(/Cost/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("renders Copy Prompt button with testid", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        expect(screen.getByTestId("copy-prompt")).toBeInTheDocument();
        expect(screen.getByText(/Copy Prompt/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("renders Replay This Turn button with testid", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        expect(screen.getByTestId("replay-turn")).toBeInTheDocument();
        expect(screen.getByText(/Replay This Turn/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays duration value in the step header", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        // Duration is formatted as Xms or X.XXs
        const viewer = screen.getByTestId("prompt-viewer");
        // Check for at least one span with duration-like content (ms or s)
        expect(viewer.textContent).toMatch(/\d+(\.\d+)?[ms]/);
      },
      { timeout: 5000 },
    );
  });

  it("renders within a prompt-viewer container with correct structure", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        const viewer = screen.getByTestId("prompt-viewer");
        // Should have the space-y-3 content area
        expect(viewer.querySelector(".space-y-3")).toBeInTheDocument();
        // Should have action buttons area
        expect(viewer.querySelector("[data-testid='copy-prompt']")).toBeInTheDocument();
        expect(viewer.querySelector("[data-testid='replay-turn']")).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays turn selector or single entry view", async () => {
    renderViewer("conv-1");
    await waitFor(
      () => {
        const viewer = screen.getByTestId("prompt-viewer");
        // Either shows the turn selector (multiple LLM entries) or just shows content directly
        const selector = viewer.querySelector("[data-testid='prompt-turn-selector']");
        const stepBadge = screen.getByText(/Step/i);
        // One of these must be present
        expect(selector || stepBadge).toBeTruthy();
      },
      { timeout: 5000 },
    );
  });
});
