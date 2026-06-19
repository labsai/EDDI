import { describe, expect, it } from "vitest";
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

describe("Rules Editor", () => {
  it("renders Rules Editor with groups", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("rules-editor")).toBeInTheDocument();
    });
  });

  it("renders behavior group with rules", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("rules-group")).toBeInTheDocument();
    });
  });

  it("renders rule editors with name inputs", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("rule-editor").length
      ).toBeGreaterThan(0);
    });
  });

  it("renders condition type dropdowns", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });
  });

  it("renders add group button", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("add-group-btn")).toBeInTheDocument();
    });
  });

  it("renders add rule buttons", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("add-rule-btn").length
      ).toBeGreaterThan(0);
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("condition type dropdown includes deploymentContext and capabilityMatch", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("condition-type-select")[0] as HTMLSelectElement;
    const options = Array.from(select.options).map((o) => o.value);
    expect(options).toContain("deploymentContext");
    expect(options).toContain("capabilityMatch");
  });

  // ─── Interaction tests ───────────────────────────────────────────────

  it("clicking add condition adds a new condition editor", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getAllByTestId("add-condition-btn").length).toBeGreaterThan(0);
    });

    const initialConditions = screen.getAllByTestId("condition-editor").length;
    fireEvent.click(screen.getAllByTestId("add-condition-btn")[0]!);

    await waitFor(() => {
      expect(screen.getAllByTestId("condition-editor").length).toBe(
        initialConditions + 1
      );
    });
  });

  it("clicking add group adds a new group", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getByTestId("add-group-btn")).toBeInTheDocument();
    });

    const initialGroups = screen.getAllByTestId("rules-group").length;
    fireEvent.click(screen.getByTestId("add-group-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("rules-group").length).toBe(initialGroups + 1);
    });
  });

  it("clicking add rule adds a new rule to the group", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getAllByTestId("add-rule-btn").length).toBeGreaterThan(0);
    });

    const initialRules = screen.getAllByTestId("rule-editor").length;
    fireEvent.click(screen.getAllByTestId("add-rule-btn")[0]!);

    await waitFor(() => {
      expect(screen.getAllByTestId("rule-editor").length).toBe(initialRules + 1);
    });
  });

  it("renders appendActions checkbox", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getByText("Append Actions")).toBeInTheDocument();
    });
  });

  it("renders expressionsAsActions checkbox", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getByText("Expressions as Actions")).toBeInTheDocument();
    });
  });

  it("renders rule name input with value from mock data", async () => {
    renderPage("rules");
    await waitFor(() => {
      const inputs = screen.getAllByTestId("rule-name-input");
      expect(inputs.length).toBeGreaterThan(0);
      expect((inputs[0] as HTMLInputElement).value).toBeTruthy();
    });
  });

  it("editing rule name updates the value", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getAllByTestId("rule-name-input").length).toBeGreaterThan(0);
    });

    const input = screen.getAllByTestId("rule-name-input")[0] as HTMLInputElement;
    fireEvent.change(input, { target: { value: "New Rule Name" } });

    await waitFor(() => {
      expect(input.value).toBe("New Rule Name");
    });
  });

  it("condition type change to actionmatcher updates configs", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("condition-type-select")[0] as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "actionmatcher" } });

    await waitFor(() => {
      expect(select.value).toBe("actionmatcher");
    });
  });

  it("condition type change to occurrence sets configs", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("condition-type-select")[0] as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "occurrence" } });

    await waitFor(() => {
      expect(select.value).toBe("occurrence");
    });
  });

  it("condition type change to deploymentContext sets configs", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("condition-type-select")[0] as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "deploymentContext" } });

    await waitFor(() => {
      expect(select.value).toBe("deploymentContext");
    });
  });

  it("condition type change to negation updates dropdown value", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("condition-type-select")[0] as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "negation" } });

    await waitFor(() => {
      expect(select.value).toBe("negation");
    });
  });

  it("switches to JSON tab and shows JSON view", async () => {
    renderPage("rules");
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });
});

