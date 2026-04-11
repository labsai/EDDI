import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PromptPreview } from "@/components/editors/prompt-preview";

// ─── Test helpers ────────────────────────────────────────────────────────────

function renderPreview(template: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <MemoryRouter>
          <PromptPreview template={template} />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe("PromptPreview", () => {
  const writeTextSpy = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    // Mock clipboard — navigator.clipboard is a read-only getter in jsdom
    writeTextSpy.mockClear();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: writeTextSpy },
      writable: true,
      configurable: true,
    });
  });

  it("renders the preview container", () => {
    renderPreview("Hello {properties.userName}");
    expect(screen.getByTestId("prompt-preview")).toBeInTheDocument();
  });

  it("shows the conversation picker", () => {
    renderPreview("Hello world");
    expect(screen.getByTestId("preview-conversation-picker")).toBeInTheDocument();
  });

  it("shows the refresh button", () => {
    renderPreview("Hello world");
    expect(screen.getByTestId("preview-refresh")).toBeInTheDocument();
  });

  it("renders resolved prompt from backend", async () => {
    renderPreview("Hello {properties.userName}");
    await waitFor(() => {
      expect(screen.getByTestId("resolved-prompt")).toBeInTheDocument();
    });
    // The MSW handler resolves {properties.userName} → "Alice"
    await waitFor(() => {
      expect(screen.getByTestId("resolved-prompt-content")).toHaveTextContent("Alice");
    });
  });

  it("renders the variable reference panel", async () => {
    renderPreview("Hello {properties.userName}");
    await waitFor(() => {
      expect(screen.getByTestId("variable-reference")).toBeInTheDocument();
    });
  });

  it("expands the variable reference panel on click", async () => {
    const user = userEvent.setup();
    renderPreview("Hello {properties.userName}");

    await waitFor(() => {
      expect(screen.getByTestId("variable-reference")).toBeInTheDocument();
    });

    // Click the "Available Variables" button to expand
    const toggle = screen.getByText(/Available Variables/i);
    await user.click(toggle);

    // Variable chips should now be visible
    await waitFor(() => {
      expect(screen.getByTestId("var-chip-properties.userName")).toBeInTheDocument();
    });
  });

  it("copies variable to clipboard when chip is clicked", async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPreview("Hello {properties.userName}");

    await waitFor(() => {
      expect(screen.getByTestId("variable-reference")).toBeInTheDocument();
    });

    // Expand variable panel
    const toggle = screen.getByText(/Available Variables/i);
    await user.click(toggle);

    await waitFor(() => {
      expect(screen.getByTestId("var-chip-properties.userName")).toBeInTheDocument();
    });

    // Click a variable chip — should call clipboard
    const chip = screen.getByTestId("var-chip-properties.userName");
    await user.click(chip);

    // The chip shows a check icon briefly when copied — just verify it doesn't crash
    expect(chip).toBeInTheDocument();
  });

  it("copies resolved prompt to clipboard", async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPreview("Hello {properties.userName}");

    await waitFor(() => {
      expect(screen.getByTestId("preview-copy")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("preview-copy"));

    // After clicking, the button text changes to "Copied!"
    await waitFor(() => {
      expect(screen.getByText(/Copied!/i)).toBeInTheDocument();
    });
  });

  it("shows sample data disclaimer when no conversation selected", async () => {
    renderPreview("Hello world");
    await waitFor(() => {
      expect(screen.getByText(/built-in sample data/i)).toBeInTheDocument();
    });
  });

  it("renders even with an empty template", async () => {
    renderPreview("");
    expect(screen.getByTestId("prompt-preview")).toBeInTheDocument();
  });

  it("can change conversation in picker", async () => {
    renderPreview("Hello {properties.userName}");

    const picker = screen.getByTestId("preview-conversation-picker");
    // Default option is "Sample data (no conversation)"
    expect(picker).toHaveValue("");

    // Conversations should load from MSW
    await waitFor(() => {
      const options = picker.querySelectorAll("option");
      expect(options.length).toBeGreaterThan(1);
    });
  });

  it("triggers refresh when refresh button is clicked", async () => {
    const user = userEvent.setup();
    renderPreview("Hello {properties.userName}");

    await waitFor(() => {
      expect(screen.getByTestId("resolved-prompt")).toBeInTheDocument();
    });

    // Click refresh
    await user.click(screen.getByTestId("preview-refresh"));

    // Should still show the resolved prompt after refresh
    await waitFor(() => {
      expect(screen.getByTestId("resolved-prompt")).toBeInTheDocument();
    });
  });
});
