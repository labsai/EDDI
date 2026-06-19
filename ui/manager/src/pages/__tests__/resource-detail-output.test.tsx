import { describe, expect, it } from "vitest";
import { screen, waitFor, fireEvent, render } from "@testing-library/react";
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

describe("Output Editor", () => {
  it("renders output editor", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("output-editor")).toBeInTheDocument();
    });
  });

  it("renders output config entries", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders action name input", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-action-input").length).toBeGreaterThan(0);
    });
  });

  it("renders output item rows", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);
    });
  });

  it("renders quick replies section", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("quickreplies-section").length).toBeGreaterThan(0);
    });
  });

  it("renders quick reply rows", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("quickreply-row").length).toBeGreaterThan(0);
    });
  });

  it("renders add output set button", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("add-output-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  // ─── Interaction tests ──────────────────────────────────────────────────

  it("clicking add output button adds a new output set", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("add-output-btn")).toBeInTheDocument();
    });

    const initialCount = screen.getAllByTestId("output-config-editor").length;
    await user.click(screen.getByTestId("add-output-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBe(initialCount + 1);
    });
  });

  it("switching to JSON tab shows JSON view", async () => {
    renderPage("output");
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
    renderPage("output");
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

  // ─── Interaction tests (callbacks) ────────────────────────────────────

  it("changing output type select updates the item", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);
    });

    const firstRow = screen.getAllByTestId("output-item-row")[0]!;
    const select = firstRow.querySelector("select") as HTMLSelectElement;
    expect(select).toBeTruthy();
    expect(select.value).toBe("text");

    fireEvent.change(select, { target: { value: "image" } });

    await waitFor(() => {
      expect(select.value).toBe("image");
    });
  });

  it("typing into output text input updates the item", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);
    });

    const firstRow = screen.getAllByTestId("output-item-row")[0]!;
    const input = firstRow.querySelector("input[type='text']") as HTMLInputElement;
    expect(input).toBeTruthy();

    fireEvent.change(input, { target: { value: "Updated output text" } });

    await waitFor(() => {
      expect(input.value).toBe("Updated output text");
    });
  });

  it("removing an output item row removes it", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("output-item-row").length;
    // The X button is inside the first output-item-row
    const firstRow = screen.getAllByTestId("output-item-row")[0]!;
    // The button with <X> icon is the last button in the row (after select)
    const buttons = firstRow.querySelectorAll("button");
    const xButton = buttons[buttons.length - 1]!;
    expect(xButton).toBeTruthy();

    await user.click(xButton);

    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBe(initialCount - 1);
    });
  });

  it("adding an alternative adds a new item to the group", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("output-item-row").length;
    const addAltBtns = screen.getAllByText("Add Alternative");
    expect(addAltBtns.length).toBeGreaterThan(0);

    await user.click(addAltBtns[0]!);

    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBe(initialCount + 1);
    });
  });

  it("adding an output group adds a new group", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBeGreaterThan(0);
    });

    // Count dashed-border group containers inside the first config editor
    const firstConfig = screen.getAllByTestId("output-config-editor")[0]!;
    const initialGroups = firstConfig.querySelectorAll("[class*='border-dashed']").length;

    const addGroupBtns = screen.getAllByText("Add Output Group");
    expect(addGroupBtns.length).toBeGreaterThan(0);

    await user.click(addGroupBtns[0]!);

    await waitFor(() => {
      const updatedGroups = screen.getAllByTestId("output-config-editor")[0]!
        .querySelectorAll("[class*='border-dashed']").length;
      expect(updatedGroups).toBeGreaterThan(initialGroups);
    });
  });

  it("adding a quick reply adds a new row", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("quickreply-row").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("quickreply-row").length;
    const addQrBtns = screen.getAllByText("Add Quick Reply");
    expect(addQrBtns.length).toBeGreaterThan(0);

    await user.click(addQrBtns[0]!);

    await waitFor(() => {
      expect(screen.getAllByTestId("quickreply-row").length).toBe(initialCount + 1);
    });
  });

  it("removing a quick reply removes the row", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("quickreply-row").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("quickreply-row").length;
    // The X button is the last button inside a quickreply-row
    const firstQr = screen.getAllByTestId("quickreply-row")[0]!;
    const buttons = firstQr.querySelectorAll("button");
    const xButton = buttons[buttons.length - 1]!;
    expect(xButton).toBeTruthy();

    await user.click(xButton!);

    await waitFor(() => {
      expect(screen.getAllByTestId("quickreply-row").length).toBe(initialCount - 1);
    });
  });

  it("collapsing an output config hides its content", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBeGreaterThan(0);
    });

    // Verify expanded content is visible (output item rows exist)
    expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);

    // The collapse toggle is the first button inside the config editor header
    const firstConfig = screen.getAllByTestId("output-config-editor")[0]!;
    const collapseBtn = firstConfig.querySelector("button") as HTMLButtonElement;
    expect(collapseBtn).toBeTruthy();

    await user.click(collapseBtn);

    await waitFor(() => {
      // After collapsing the first config, the output-item-rows inside it should disappear
      // but items from other configs may still be visible
      const itemsInFirstConfig = firstConfig!.querySelectorAll("[data-testid='output-item-row']");
      expect(itemsInFirstConfig.length).toBe(0);
    });
  });

  it("removing an output config card removes it", async () => {
    renderPage("output");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBeGreaterThan(0);
    });

    const initialCount = screen.getAllByTestId("output-config-editor").length;
    // The Remove button has aria-label="Remove"
    const removeBtns = screen.getAllByLabelText("Remove");
    expect(removeBtns.length).toBeGreaterThan(0);

    await user.click(removeBtns[0]!);

    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBe(initialCount - 1);
    });
  });

  it("editing the language input updates the value", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("output-editor")).toBeInTheDocument();
    });

    // The language input is inside the output-editor, with placeholder "e.g. en, de"
    const langInput = screen.getByPlaceholderText("e.g. en, de") as HTMLInputElement;
    expect(langInput).toBeTruthy();
    expect(langInput.value).toBe("en");

    fireEvent.change(langInput, { target: { value: "de" } });

    await waitFor(() => {
      expect(langInput.value).toBe("de");
    });
  });
});
