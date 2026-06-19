import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CapabilitiesSection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

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
  it("renders capabilities section container", async () => {
    renderSection();
    // Section is collapsed by default when no capabilities
    await waitFor(() => {
      expect(screen.getByText(/Capabilities/i)).toBeInTheDocument();
    });
  });

  it("shows capabilities description when expanded", async () => {
    renderSection({ capabilities: [{ skill: "test-skill", confidence: "high" }] });
    await waitFor(() => {
      expect(screen.getByTestId("capabilities-section")).toBeInTheDocument();
      expect(screen.getByText(/Declared skills/i)).toBeInTheDocument();
    });
  });

  it("renders existing capabilities with skill name", async () => {
    renderSection({
      capabilities: [
        { skill: "customer-support", confidence: "high" },
        { skill: "code-review", confidence: "medium" },
      ],
    });
    await waitFor(() => {
      expect(screen.getByText("customer-support")).toBeInTheDocument();
      expect(screen.getByText("code-review")).toBeInTheDocument();
    });
  });

  it("renders confidence selects for each capability", async () => {
    renderSection({
      capabilities: [
        { skill: "test-skill", confidence: "high" },
      ],
    });
    await waitFor(() => {
      expect(screen.getByTestId("confidence-select-0")).toBeInTheDocument();
      expect(screen.getByTestId("confidence-select-0")).toHaveValue("high");
    });
  });

  it("shows the skill autocomplete input", async () => {
    renderSection({ capabilities: [{ skill: "x", confidence: "low" }] });
    await waitFor(() => {
      expect(screen.getByTestId("skill-autocomplete-input")).toBeInTheDocument();
    });
  });

  it("shows add capability button", async () => {
    renderSection({ capabilities: [{ skill: "x", confidence: "low" }] });
    await waitFor(() => {
      expect(screen.getByTestId("add-capability-btn")).toBeInTheDocument();
    });
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

  it("renders attribute count pill when attributes exist", async () => {
    renderSection({
      capabilities: [
        { skill: "my-skill", confidence: "medium", attributes: { lang: "en", domain: "tech" } },
      ],
    });
    await waitFor(() => {
      expect(screen.getByText("2 attrs")).toBeInTheDocument();
    });
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

  // ─── Interaction tests ────────────────────────────────────────────────

  it("adds a new capability via add button and calls update API", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent-test?version=2" },
        });
      })
    );

    renderSection({ capabilities: [] });
    const user = userEvent.setup();

    // Expand the section first
    await user.click(screen.getByText(/Capabilities/i));

    await waitFor(() => {
      expect(screen.getByTestId("skill-autocomplete-input")).toBeInTheDocument();
    });

    // Type a new skill and click add
    const input = screen.getByTestId("skill-autocomplete-input");
    await user.type(input, "new-skill");
    await user.click(screen.getByTestId("add-capability-btn"));

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });

  it("removes a capability and calls update API", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent-test?version=2" },
        });
      })
    );

    renderSection({
      capabilities: [{ skill: "to-remove", confidence: "low" }],
    });
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("to-remove")).toBeInTheDocument();
    });

    // The remove button is the X icon in the capability entry
    const capEntry = screen.getByTestId("capability-entry-0");
    const removeBtn = capEntry.querySelector('button[class*="destructive"]') as HTMLButtonElement;
    expect(removeBtn).toBeTruthy();
    await user.click(removeBtn);

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });

  it("changes confidence level and calls update API", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent-test?version=2" },
        });
      })
    );

    renderSection({
      capabilities: [{ skill: "test-skill", confidence: "medium" }],
    });
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("confidence-select-0")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("confidence-select-0"), "high");

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });

  it("selects an autocomplete item and calls update API", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent-test?version=2" },
        });
      })
    );

    renderSection({ capabilities: [] });
    const user = userEvent.setup();

    // Expand section
    await user.click(screen.getByText(/Capabilities/i));

    await waitFor(() => {
      expect(screen.getByTestId("skill-autocomplete-input")).toBeInTheDocument();
    });

    // Type to trigger autocomplete — "customer" should match "customer-support" from skills MSW handler
    const input = screen.getByTestId("skill-autocomplete-input");
    await user.type(input, "customer");

    await waitFor(() => {
      expect(screen.getByTestId("skill-autocomplete-dropdown")).toBeInTheDocument();
    });

    // Click the autocomplete suggestion
    const suggestion = screen.getByText("customer-support");
    await user.click(suggestion);

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });
});
