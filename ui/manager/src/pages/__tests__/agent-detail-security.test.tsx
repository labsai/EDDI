import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { SecurityIdentitySection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";
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
  it("renders the section header", async () => {
    renderSection();
    await waitFor(() => {
      expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
    });
  });

  it("renders all three security flag labels when expanded", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
      expect(screen.getByText(/Sign MCP invocations/i)).toBeInTheDocument();
      expect(screen.getByText(/Require peer verification/i)).toBeInTheDocument();
    });
  });

  it("does NOT show warning banner when no flags are enabled", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
    });

    expect(screen.queryByTestId("security-flag-warning")).not.toBeInTheDocument();
  });

  it("shows warning banner when a flag is enabled", async () => {
    renderSection({
      security: {
        signInterAgentMessages: true,
        signMcpInvocations: false,
        requirePeerVerification: false,
      },
    });

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-warning")).toBeInTheDocument();
      expect(screen.getByText(/Cryptographic signing is not yet available/i)).toBeInTheDocument();
    });
  });

  it("shows confirmation dialog when toggling a flag ON", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-signInterAgentMessages")).toBeInTheDocument();
    });

    // Use the specific data-testid instead of index-based selector
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));

    await waitFor(() => {
      expect(screen.getByText(/Enable security flag\?/i)).toBeInTheDocument();
      expect(screen.getByText(/Enable anyway/i)).toBeInTheDocument();
    });
  });

  it("closes confirmation dialog when cancel is clicked and checkbox remains unchecked", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-signInterAgentMessages")).toBeInTheDocument();
    });

    const checkbox = screen.getByTestId("security-flag-signInterAgentMessages") as HTMLInputElement;

    // Verify checkbox starts unchecked
    expect(checkbox.checked).toBe(false);

    await user.click(checkbox);

    await waitFor(() => {
      expect(screen.getByText(/Enable security flag\?/i)).toBeInTheDocument();
    });

    await user.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(screen.queryByText(/Enable security flag\?/i)).not.toBeInTheDocument();
    });

    // After cancel, the checkbox should still be unchecked
    expect(checkbox.checked).toBe(false);
  });

  it("clicking 'Enable anyway' confirms the flag and calls update API", async () => {
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

    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-signInterAgentMessages")).toBeInTheDocument();
    });

    // Click the checkbox to open the confirmation dialog
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));

    await waitFor(() => {
      expect(screen.getByText(/Enable anyway/i)).toBeInTheDocument();
    });

    // Click "Enable anyway" to confirm
    await user.click(screen.getByText(/Enable anyway/i));

    // The dialog should close and the API should be called
    await waitFor(() => {
      expect(screen.queryByText(/Enable security flag\?/i)).not.toBeInTheDocument();
      expect(updateCalled).toBe(true);
    });
  });

  it("renders identity fields when expanded", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("identity-section")).toBeInTheDocument();
      expect(screen.getByText("Cryptographic Identity")).toBeInTheDocument();
    });
  });

  it("shows security toggles section", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
    });
  });

  it("renders all three checkboxes with correct data-testids", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-signInterAgentMessages")).toBeInTheDocument();
      expect(screen.getByTestId("security-flag-signMcpInvocations")).toBeInTheDocument();
      expect(screen.getByTestId("security-flag-requirePeerVerification")).toBeInTheDocument();
    });
  });
});
