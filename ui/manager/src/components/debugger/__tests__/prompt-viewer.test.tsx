import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PromptViewer } from "@/components/debugger/prompt-viewer";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { userEvent } from "@/test/test-utils";

function renderViewer(conversationId: string | null = "conv1") {
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

/** Build a proper audit entry with camelCase llmDetail keys */
function makeLlmEntry(overrides: Record<string, unknown> = {}) {
  return {
    id: "a1",
    conversationId: "conv-test",
    agentId: "agent1",
    agentVersion: 1,
    userId: "user-1",
    environment: "production",
    stepIndex: 0,
    taskId: "ai.labs.llm",
    taskType: "langchain",
    taskIndex: 0,
    durationMs: 300,
    input: {},
    output: {},
    llmDetail: {
      compiledPrompt: JSON.stringify([
        { role: "system", content: "You are a helpful assistant" },
        { role: "user", content: "Hello there" },
      ]),
      modelResponse: "Hi! How can I help?",
      modelName: "gpt-4",
      tokenUsage: {
        inputTokens: 42,
        outputTokens: 12,
      },
    },
    toolCalls: null,
    actions: null,
    cost: 0.003,
    timestamp: new Date(Date.now() - 60000).toISOString(),
    hmac: "h1",
    agentSignature: "s1",
    ...overrides,
  };
}

describe("PromptViewer", () => {
  beforeEach(() => {
    localStorage.clear();
    // Set default audit handler for all tests - returns standard LLM entry
    server.use(
      http.get("*/auditstore/:conversationId", ({ params }) => {
        // Guard so /count doesn't match
        if (params.conversationId === "count") return;
        return HttpResponse.json([makeLlmEntry()]);
      })
    );
  });

  // ── Empty / error states ───────────────────────────────────────────
  it("renders empty state when no conversationId", () => {
    renderViewer(null);
    expect(screen.getByText(/Start a conversation to inspect prompts/i)).toBeInTheDocument();
  });

  it("shows error state when API returns error", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json(null, { status: 500 });
      })
    );

    renderViewer("conv-error");

    await waitFor(() => {
      expect(screen.getByTestId("prompt-viewer-error")).toBeInTheDocument();
      expect(screen.getByText(/Failed to load prompt data/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("shows 'No LLM interactions' when audit has no llmDetail entries", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([
          {
            id: "a1", conversationId: "conv-no-llm", agentId: "agent1", agentVersion: 1,
            userId: "user-1", environment: "production", stepIndex: 0,
            taskId: "ai.labs.parser", taskType: "expressions", taskIndex: 0,
            durationMs: 10, input: {}, output: {},
            llmDetail: null, toolCalls: null, actions: null, cost: 0,
            timestamp: new Date().toISOString(),
            hmac: "abc", agentSignature: "sig",
          },
        ]);
      })
    );

    renderViewer("conv-no-llm");

    await waitFor(() => {
      expect(screen.getByText(/No LLM interactions found/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Data rendering ─────────────────────────────────────────────────
  it("renders prompt-viewer testid when LLM data is found", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-llm");

    await waitFor(() => {
      expect(screen.getByTestId("prompt-viewer")).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("displays step index number", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-step");

    await waitFor(() => {
      expect(screen.getByText(/Step/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("displays task type badge", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-type");

    await waitFor(() => {
      expect(screen.getByText(/langchain/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("displays cost in the metrics strip", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-cost");

    await waitFor(() => {
      expect(screen.getByText(/Cost/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("displays model name in metrics strip", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-model");

    await waitFor(() => {
      expect(screen.getByText(/Model/i)).toBeInTheDocument();
      expect(screen.getByText(/gpt-4/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("displays token usage in metrics strip", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-tokens");

    await waitFor(() => {
      expect(screen.getByText(/Tokens/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Action buttons ─────────────────────────────────────────────────
  it("renders Copy Prompt button", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-copy");

    await waitFor(() => {
      expect(screen.getByTestId("copy-prompt")).toBeInTheDocument();
      expect(screen.getByText(/Copy Prompt/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("renders Replay This Turn button", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-replay");

    await waitFor(() => {
      expect(screen.getByTestId("replay-turn")).toBeInTheDocument();
      expect(screen.getByText(/Replay This Turn/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Message cards from JSON prompt ─────────────────────────────────
  it("renders system and user message cards from JSON prompt", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([makeLlmEntry()]);
      })
    );

    renderViewer("conv-json");

    await waitFor(() => {
      expect(screen.getByText(/System Prompt/i)).toBeInTheDocument();
      expect(screen.getByText(/You are a helpful assistant/)).toBeInTheDocument();
      expect(screen.getByText(/Hello there/)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  it("renders assistant response card", async () => {
    renderViewer("conv-assistant");

    await waitFor(() => {
      // "Assistant" appears both as role label and in system prompt content
      // so we use getAllByText and check at least 2 matches
      const matches = screen.getAllByText(/Assistant/i);
      expect(matches.length).toBeGreaterThanOrEqual(2);
    }, { timeout: 5000 });
  });

  // ── Text-based prompt parsing ──────────────────────────────────────
  it("parses text-based prompts with role markers", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([
          makeLlmEntry({
            llmDetail: {
              compiledPrompt: "System: You are helpful\nUser: What is 2+2?",
              modelResponse: "4",
              modelName: "gpt-4",
            },
          }),
        ]);
      })
    );

    renderViewer("conv-text");

    await waitFor(() => {
      expect(screen.getByText(/System Prompt/i)).toBeInTheDocument();
      expect(screen.getByText(/You are helpful/)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Tool calls display ─────────────────────────────────────────────
  it("displays tool calls section when present", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([
          makeLlmEntry({
            toolCalls: [
              { name: "search", arguments: { query: "test" } },
            ],
          }),
        ]);
      })
    );

    renderViewer("conv-tools");

    await waitFor(() => {
      expect(screen.getByText(/Tool Calls/i)).toBeInTheDocument();
      expect(screen.getByText(/search/)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Multiple LLM entries with turn selector ────────────────────────
  it("shows turn selector when multiple LLM entries exist", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([
          makeLlmEntry({ id: "a1", stepIndex: 0 }),
          makeLlmEntry({ id: "a2", stepIndex: 1, timestamp: new Date(Date.now() - 30000).toISOString() }),
        ]);
      })
    );

    renderViewer("conv-multi");

    await waitFor(() => {
      expect(screen.getByTestId("prompt-turn-selector")).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Copy button behavior ───────────────────────────────────────────
  it("copy button is clickable without errors", async () => {
    renderViewer("conv-clipboard");

    await waitFor(() => {
      expect(screen.getByTestId("copy-prompt")).toBeInTheDocument();
    }, { timeout: 5000 });

    // Click the copy button — it should not throw even if clipboard API is unavailable
    // The component catches clipboard errors gracefully
    const user = userEvent.setup();
    await user.click(screen.getByTestId("copy-prompt"));

    // The button text should still be "Copy Prompt" (or "Copied!" if clipboard worked)
    expect(screen.getByTestId("copy-prompt")).toBeInTheDocument();
  });

  // ── Duration display edge case ─────────────────────────────────────
  it("hides duration badge when durationMs is 0", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", ({ params }) => {
        if (params.conversationId === "count") return;
        return HttpResponse.json([makeLlmEntry({ durationMs: 0 })]);
      })
    );

    renderViewer("conv-zero-dur");

    await waitFor(() => {
      expect(screen.getByTestId("prompt-viewer")).toBeInTheDocument();
    }, { timeout: 5000 });

    // When durationMs is 0, the duration span should not render
    const viewer = screen.getByTestId("prompt-viewer");
    expect(viewer.textContent).not.toContain("300ms");
  });

  // ── Cost display threshold ─────────────────────────────────────────
  it("hides cost when cost is 0", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", ({ params }) => {
        if (params.conversationId === "count") return;
        return HttpResponse.json([makeLlmEntry({ cost: 0 })]);
      })
    );

    renderViewer("conv-no-cost");

    await waitFor(() => {
      expect(screen.getByTestId("prompt-viewer")).toBeInTheDocument();
    }, { timeout: 5000 });

    expect(screen.queryByText(/\bCost\b/)).not.toBeInTheDocument();
  });

  it("shows cost with 4 decimal places for small amounts", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", ({ params }) => {
        if (params.conversationId === "count") return;
        return HttpResponse.json([makeLlmEntry({ cost: 0.0042 })]);
      })
    );

    renderViewer("conv-small-cost");

    await waitFor(() => {
      expect(screen.getByText(/0\.0042/)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── No compiled prompt fallback ────────────────────────────────────
  it("handles llmDetail without compiledPrompt gracefully", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", ({ params }) => {
        if (params.conversationId === "count") return;
        return HttpResponse.json([
          makeLlmEntry({
            llmDetail: {
              modelName: "gpt-4",
              modelResponse: "Hello!",
            },
          }),
        ]);
      })
    );

    renderViewer("conv-no-prompt");

    await waitFor(() => {
      expect(screen.getByTestId("prompt-viewer")).toBeInTheDocument();
      // Assistant card label is visible (content is collapsed by default)
      expect(screen.getByText(/Assistant/i)).toBeInTheDocument();
    }, { timeout: 5000 });
  });
});

