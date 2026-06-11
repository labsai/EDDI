import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";
import userEvent from "@testing-library/user-event";

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

describe("Dictionary Editor", () => {
  it("renders dictionary editor", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getByTestId("dictionary-editor")).toBeInTheDocument();
    });
  });

  it("renders word rows", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBeGreaterThan(0);
    });
  });

  it("renders phrase rows", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getAllByTestId("phrase-row").length).toBeGreaterThan(0);
    });
  });

  it("renders regex rows", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getAllByTestId("regex-row").length).toBeGreaterThan(0);
    });
  });

  it("renders add word button", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getByTestId("add-words-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  // ─── Interaction tests ──────────────────────────────────────────────────

  it("clicking add word button adds a new word row", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("add-words-btn")).toBeInTheDocument();
    });

    const initialCount = screen.getAllByTestId("word-row").length;
    await user.click(screen.getByTestId("add-words-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBe(initialCount + 1);
    });
  });

  it("switching to JSON tab shows JSON view", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("switching back to form tab shows form view", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    // Go to JSON
    await user.click(screen.getByTestId("tab-json"));
    expect(screen.getByTestId("json-view")).toBeInTheDocument();

    // Go back to Form
    await user.click(screen.getByTestId("tab-form"));
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
  });

  // ─── Add phrase / regex ─────────────────────────────────────────────────

  it("clicking add phrase button adds a new phrase row", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("add-phrases-btn")).toBeInTheDocument();
    });

    const initialCount = screen.getAllByTestId("phrase-row").length;
    await user.click(screen.getByTestId("add-phrases-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("phrase-row").length).toBe(
        initialCount + 1,
      );
    });
  });

  it("clicking add regex button adds a new regex row", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(
        screen.getByTestId("add-regular expressions-btn"),
      ).toBeInTheDocument();
    });

    const initialCount = screen.getAllByTestId("regex-row").length;
    await user.click(screen.getByTestId("add-regular expressions-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("regex-row").length).toBe(
        initialCount + 1,
      );
    });
  });

  // ─── Editing inputs ────────────────────────────────────────────────────

  it("editing a word input updates the word", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBeGreaterThan(0);
    });

    const firstWordRow = screen.getAllByTestId("word-row")[0];
    const wordInput = within(firstWordRow).getAllByRole("textbox")[0];

    await user.clear(wordInput);
    await user.type(wordInput, "bonjour");

    expect(wordInput).toHaveValue("bonjour");
  });

  it("editing a word expression input updates the expression", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBeGreaterThan(0);
    });

    const firstWordRow = screen.getAllByTestId("word-row")[0];
    // Second textbox is the expressions input (first is the word itself)
    const expressionInput = within(firstWordRow).getAllByRole("textbox")[1];

    await user.clear(expressionInput);
    await user.type(expressionInput, "greeting(bonjour)");

    expect(expressionInput).toHaveValue("greeting(bonjour)");
  });

  it("editing a phrase input updates the phrase", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("phrase-row").length).toBeGreaterThan(0);
    });

    const firstPhraseRow = screen.getAllByTestId("phrase-row")[0];
    const phraseInput = within(firstPhraseRow).getAllByRole("textbox")[0];

    await user.clear(phraseInput);
    await user.type(phraseInput, "good afternoon");

    expect(phraseInput).toHaveValue("good afternoon");
  });

  // ─── Removing rows ─────────────────────────────────────────────────────

  it("removing a word row removes it", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("word-row").length;
    const firstWordRow = screen.getAllByTestId("word-row")[0];
    const removeBtn = within(firstWordRow).getByRole("button");

    await user.click(removeBtn);

    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBe(
        initialCount - 1,
      );
    });
  });

  it("removing a phrase row removes it", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("phrase-row").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("phrase-row").length;
    const firstPhraseRow = screen.getAllByTestId("phrase-row")[0];
    const removeBtn = within(firstPhraseRow).getByRole("button");

    await user.click(removeBtn);

    await waitFor(() => {
      expect(screen.getAllByTestId("phrase-row").length).toBe(
        initialCount - 1,
      );
    });
  });

  // ─── Language input ─────────────────────────────────────────────────────

  it("editing the language input updates the value", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("dictionary-editor")).toBeInTheDocument();
    });

    const langInput = screen.getByPlaceholderText("e.g. en, de");
    await user.clear(langInput);
    await user.type(langInput, "fr");

    expect(langInput).toHaveValue("fr");
  });
});

