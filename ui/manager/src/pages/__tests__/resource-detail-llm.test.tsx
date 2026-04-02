import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderPage(type: string, id = "res1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/resources/${type}/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/manage/resources/:type/:id"
              element={<ResourceDetailPage />}
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("LangChain Editor", () => {
  it("renders LLM editor with tasks", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("llm-editor")).toBeInTheDocument();
    });
  });

  it("renders task card with model type select", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("llm-task-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders model type dropdown", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("model-type-select").length).toBeGreaterThan(0);
    });
  });

  it("renders mode badge (agent/chat)", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("mode-badge").length).toBeGreaterThan(0);
    });
  });

  it("renders system prompt content editor", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("system-prompt").length).toBeGreaterThan(0);
    });
  });

  it("renders enable built-in tools checkbox", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("enable-builtin-tools").length).toBeGreaterThan(0);
    });
  });

  it("renders add task button", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("add-task-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders add A2A agent button", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("add-a2a-agent")).toBeInTheDocument();
    });
  });

  it("renders A2A agent config from mock data", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("a2a-agent-0")).toBeInTheDocument();
    });
  });

  it("shows A2A Agents section header", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("A2A Agents")).toBeInTheDocument();
    });
  });

  it("renders A2A agent URL from mock data", async () => {
    renderPage("llm");
    await waitFor(() => {
      const urlInput = screen.getByDisplayValue("https://remote.example.com/a2a/agents/support");
      expect(urlInput).toBeInTheDocument();
    });
  });

  it("renders A2A agent name from mock data", async () => {
    renderPage("llm");
    await waitFor(() => {
      const nameInput = screen.getByDisplayValue("Support Agent");
      expect(nameInput).toBeInTheDocument();
    });
  });

  it("renders model cascade section header", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("Model Cascade")).toBeInTheDocument();
    });
  });

  it("renders cascade enable toggle", async () => {
    renderPage("llm");
    // The Model Cascade section has defaultOpen=true because mock data has enabled=true
    await waitFor(() => {
      expect(screen.getByTestId("cascade-enable")).toBeInTheDocument();
    });
  });

  it("renders cascade step from mock data", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByDisplayValue("gpt-5.4-mini")).toBeInTheDocument();
    });
  });

  it("renders budget and costs section header", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("Budget & Costs")).toBeInTheDocument();
    });
  });

  it("renders execution section header", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });
  });

  it("renders retry section header", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("Retry Configuration")).toBeInTheDocument();
    });
  });

  it("renders RAG section header", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });
  });

  // ─── RAG Section Interaction Tests ──────────────────────────

  it("renders httpCall RAG input when RAG section is expanded", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    // Click the RAG section header to expand it
    fireEvent.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("httpcall-rag")).toBeInTheDocument();
    });
  });

  it("renders enable-workflow-rag checkbox when RAG section is expanded", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("enable-workflow-rag")).toBeInTheDocument();
    });
  });

  it("renders add KB reference button", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("add-kb-ref")).toBeInTheDocument();
    });
  });

  it("adds a KB reference when button is clicked", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("add-kb-ref")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("add-kb-ref"));

    await waitFor(() => {
      expect(screen.getByTestId("kb-name-0")).toBeInTheDocument();
    });
  });

  it("types a value into httpCall RAG input", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("httpcall-rag")).toBeInTheDocument();
    });

    const input = screen.getByTestId("httpcall-rag") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "search_docs" } });

    await waitFor(() => {
      expect(input.value).toBe("search_docs");
    });
  });
});
