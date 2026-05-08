import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CapabilitiesSection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";
import userEvent from "@testing-library/user-event";

function renderSection(agentOverrides: Partial<Agent> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const agent: Agent = {
    workflows: [],
    capabilities: [],
    ...agentOverrides,
  };

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-cap-editor">
        <CapabilitiesSection agent={agent} agentId="agent-test" version={1} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe("CapabilitiesSection — Editor", () => {
  it("renders capabilities section container", () => {
    renderSection();
    // Section is collapsed by default when no capabilities
    expect(screen.getByText(/Capabilities/i)).toBeInTheDocument();
  });

  it("shows capabilities description when expanded", () => {
    renderSection({ capabilities: [{ skill: "test-skill", confidence: "high" }] });
    expect(screen.getByTestId("capabilities-section")).toBeInTheDocument();
    expect(screen.getByText(/Declared skills/i)).toBeInTheDocument();
  });

  it("renders existing capabilities with skill name", () => {
    renderSection({
      capabilities: [
        { skill: "customer-support", confidence: "high" },
        { skill: "code-review", confidence: "medium" },
      ],
    });
    expect(screen.getByText("customer-support")).toBeInTheDocument();
    expect(screen.getByText("code-review")).toBeInTheDocument();
  });

  it("renders confidence selects for each capability", () => {
    renderSection({
      capabilities: [
        { skill: "test-skill", confidence: "high" },
      ],
    });
    expect(screen.getByTestId("confidence-select-0")).toBeInTheDocument();
    expect(screen.getByTestId("confidence-select-0")).toHaveValue("high");
  });

  it("shows the skill autocomplete input", () => {
    renderSection({ capabilities: [{ skill: "x", confidence: "low" }] });
    expect(screen.getByTestId("skill-autocomplete-input")).toBeInTheDocument();
  });

  it("shows add capability button", () => {
    renderSection({ capabilities: [{ skill: "x", confidence: "low" }] });
    expect(screen.getByTestId("add-capability-btn")).toBeInTheDocument();
  });

  it("shows autocomplete dropdown when typing a matching skill", async () => {
    renderSection({ capabilities: [{ skill: "existing", confidence: "low" }] });
    const user = userEvent.setup();

    const input = screen.getByTestId("skill-autocomplete-input");
    await user.click(input);
    await user.type(input, "customer");

    await waitFor(() => {
      expect(screen.getByTestId("skill-autocomplete-dropdown")).toBeInTheDocument();
    });
  });

  it("renders attribute count pill when attributes exist", () => {
    renderSection({
      capabilities: [
        { skill: "my-skill", confidence: "medium", attributes: { lang: "en", domain: "tech" } },
      ],
    });
    expect(screen.getByText("2 attrs")).toBeInTheDocument();
  });

  it("expands attributes editor when capability icon is clicked", async () => {
    renderSection({
      capabilities: [
        { skill: "my-skill", confidence: "medium", attributes: { lang: "en" } },
      ],
    });
    const user = userEvent.setup();

    // Click the Sparkles icon button (toggle attributes)
    const toggleBtn = screen.getByTitle("Toggle attributes");
    await user.click(toggleBtn);

    await waitFor(() => {
      expect(screen.getByText("Attributes")).toBeInTheDocument();
    });
  });

  it("shows attribute key-value inputs when expanded", async () => {
    renderSection({
      capabilities: [
        { skill: "my-skill", confidence: "medium", attributes: { lang: "en" } },
      ],
    });
    const user = userEvent.setup();

    const toggleBtn = screen.getByTitle("Toggle attributes");
    await user.click(toggleBtn);

    await waitFor(() => {
      expect(screen.getByTestId("attribute-key-input")).toBeInTheDocument();
      expect(screen.getByTestId("attribute-value-input")).toBeInTheDocument();
    });
  });

  it("displays existing attribute values", async () => {
    renderSection({
      capabilities: [
        { skill: "my-skill", confidence: "medium", attributes: { languages: "en,de,fr" } },
      ],
    });
    const user = userEvent.setup();

    const toggleBtn = screen.getByTitle("Toggle attributes");
    await user.click(toggleBtn);

    await waitFor(() => {
      expect(screen.getByText("languages")).toBeInTheDocument();
      expect(screen.getByDisplayValue("en,de,fr")).toBeInTheDocument();
    });
  });
});
