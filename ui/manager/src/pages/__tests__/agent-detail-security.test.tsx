import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { SecurityIdentitySection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";

function renderSection(agentOverrides: Partial<Agent> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const agent: Agent = {
    workflows: [],
    security: {
      signInterAgentMessages: false,
      signMcpInvocations: false,
      requirePeerVerification: false,
    },
    ...agentOverrides,
  };

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-security">
        <SecurityIdentitySection agent={agent} agentId="agent-test" version={1} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe("SecurityIdentitySection — Flag UX", () => {
  it("renders the section header", () => {
    renderSection();
    expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
  });

  it("renders all three security flag labels when expanded", () => {
    renderSection();
    fireEvent.click(screen.getByText(/Security & Identity/i));

    expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
    expect(screen.getByText(/Sign MCP invocations/i)).toBeInTheDocument();
    expect(screen.getByText(/Require peer verification/i)).toBeInTheDocument();
  });

  it("does NOT show warning banner when no flags are enabled", () => {
    renderSection();
    fireEvent.click(screen.getByText(/Security & Identity/i));

    expect(screen.queryByTestId("security-flag-warning")).not.toBeInTheDocument();
  });

  it("shows warning banner when a flag is enabled", () => {
    renderSection({
      security: {
        signInterAgentMessages: true,
        signMcpInvocations: false,
        requirePeerVerification: false,
      },
    });

    expect(screen.getByTestId("security-flag-warning")).toBeInTheDocument();
    expect(screen.getByText(/Cryptographic signing is not yet available/i)).toBeInTheDocument();
  });

  it("shows confirmation dialog when toggling a flag ON", async () => {
    renderSection();
    fireEvent.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
    });

    // The checkboxes are rendered as <input type="checkbox">
    const checkboxes = screen.getAllByRole("checkbox");
    // First checkbox is signInterAgentMessages
    fireEvent.click(checkboxes[0]!);

    await waitFor(() => {
      expect(screen.getByText(/Enable security flag\?/i)).toBeInTheDocument();
      expect(screen.getByText(/Enable anyway/i)).toBeInTheDocument();
    });
  });

  it("closes confirmation dialog when cancel is clicked", async () => {
    renderSection();
    fireEvent.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
    });

    const checkboxes = screen.getAllByRole("checkbox");
    fireEvent.click(checkboxes[0]!);

    await waitFor(() => {
      expect(screen.getByText(/Enable security flag\?/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(screen.queryByText(/Enable security flag\?/i)).not.toBeInTheDocument();
    });
  });

  it("renders identity fields when expanded", () => {
    renderSection();
    fireEvent.click(screen.getByText(/Security & Identity/i));

    expect(screen.getByTestId("identity-section")).toBeInTheDocument();
    expect(screen.getByText("Cryptographic Identity")).toBeInTheDocument();
  });

  it("shows security toggles section", () => {
    renderSection();
    fireEvent.click(screen.getByText(/Security & Identity/i));

    expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
  });
});
