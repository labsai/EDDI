import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { SecurityIdentitySection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";
import { server } from "@/test/mocks/server";
import { renderPage } from "@/test/test-utils";
import { AgentDetailPage } from "@/pages/agent-detail";
import { http, HttpResponse } from "msw";

/** The page's draft setter; a section only ever reports an edit through it. */
const onChange = vi.fn();

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
      requirePeerVerification: false,
    },
    ...agentOverrides,
  };

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-security">
        <SecurityIdentitySection agent={agent} onChange={onChange} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe("SecurityIdentitySection — Flag UX", () => {
  beforeEach(() => onChange.mockReset());

  it("renders the section header", async () => {
    renderSection();
    await waitFor(() => {
      expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
    });
  });

  it("renders the two flags the backend has", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-signInterAgentMessages")).toBeInTheDocument();
      expect(screen.getByTestId("security-flag-requirePeerVerification")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("security-flag-signMcpInvocations")).not.toBeInTheDocument();
  });

  it("does NOT show the warning when no flags are enabled", async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
    });

    expect(screen.queryByTestId("security-flag-warning")).not.toBeInTheDocument();
  });

  it("warns when signing is on and the agent has no key", async () => {
    renderSection({ security: { signInterAgentMessages: true, requirePeerVerification: false } });

    await waitFor(() => {
      expect(screen.getByTestId("security-flag-warning")).toBeInTheDocument();
      expect(screen.getByText(/no signing key, so a save with signing on is refused/i)).toBeInTheDocument();
    });
  });

  it("does not warn for peer verification alone: it needs no key of the agent's own", async () => {
    renderSection({ security: { signInterAgentMessages: false, requirePeerVerification: true } });

    await waitFor(() => {
      expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("security-flag-warning")).not.toBeInTheDocument();
  });

  it("turning a flag on records the edit without saving", async () => {
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
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));

    // Recorded in the draft — and not written: saving is the page's Save.
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ security: expect.objectContaining({ signInterAgentMessages: true }) }),
    );
    expect(updateCalled).toBe(false);
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
});

describe("AgentDetailPage — Generate signing key", () => {
  it("generates a key for the version on screen, then follows the version the key was saved as", async () => {
    let postedVersion: string | null = null;
    const loadedVersions: string[] = [];
    server.use(
      http.post("*/agentstore/agents/:id/signing-keys", ({ request }) => {
        postedVersion = new URL(request.url).searchParams.get("version");
        return HttpResponse.json(
          { version: 2, publicKeyB64: "MCowBQYDK2VwAyEAnewKey", validFromMs: 1, validUntilMs: 0 },
          { status: 201, headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=9" } },
        );
      }),
      // Records which versions the page loads, then falls through to the default handler.
      http.get("*/agentstore/agents/:id", ({ request }) => {
        const version = new URL(request.url).searchParams.get("version");
        if (version) loadedVersions.push(version);
      }),
    );
    const user = userEvent.setup();
    renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");

    const button = await screen.findByTestId("generate-signing-key");
    await waitFor(() => expect(button).toBeEnabled());
    const versionOnScreen = loadedVersions[loadedVersions.length - 1];
    await user.click(button);

    await waitFor(() => expect(postedVersion).toBe(versionOnScreen));
    await waitFor(() => expect(loadedVersions).toContain("9"));
  });
});
