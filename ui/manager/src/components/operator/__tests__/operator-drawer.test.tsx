import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorDrawer } from "../operator-drawer";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";
import { useOperatorDrawerStore } from "@/hooks/use-operator-drawer";

vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: [],
    method: "none" as const,
    login: () => {},
    logout: () => {},
  }),
  useHasRole: () => true,
}));

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;

function serveConfig(config: OperatorConfig | null) {
  server.use(
    http.get(VAR_URL, () =>
      config
        ? HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: JSON.stringify(config) })
        : HttpResponse.json({ message: "not found" }, { status: 404 }),
    ),
  );
}

function activeConfig(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return {
    ...defaultOperatorConfig("Body."),
    enabled: true,
    agentId: "op-1",
    version: 2,
    ...overrides,
  };
}

describe("OperatorDrawer", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useOperatorChatStore.getState().reset();
    useOperatorDrawerStore.setState({ isOpen: false });
    server.resetHandlers();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
  });

  it("renders nothing at all on the full operator page — no redundant launcher over the real screen", () => {
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/operator" });
    expect(screen.queryByTestId("operator-drawer-fab")).not.toBeInTheDocument();
  });

  it("clears WorkforceBottomTabs' 64px bar on mobile when told to, instead of sitting under it", () => {
    // Caught live in the browser at the mobile breakpoint: the default
    // bottom-6 put ~40px of the launcher under WorkforceBottomTabs (fixed,
    // h-16, bottom-0). bottom-20 clears it with margin to spare.
    serveConfig(activeConfig());
    const { container: withoutClearance } = renderWithProviders(<OperatorDrawer />, {
      initialRoute: "/manage/agents",
    });
    expect(withoutClearance.querySelector('[class*="bottom-6"]')).toBeInTheDocument();
    expect(withoutClearance.querySelector('[class*="bottom-20"]')).not.toBeInTheDocument();

    const { container: withClearance } = renderWithProviders(<OperatorDrawer clearsBottomTabBar />, {
      initialRoute: "/manage/agents",
    });
    expect(withClearance.querySelector('[class*="bottom-20"]')).toBeInTheDocument();
    expect(withClearance.querySelector('[class*="bottom-6"]')).not.toBeInTheDocument();
  });

  it("starts closed, showing only the launcher", async () => {
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    expect(screen.getByTestId("operator-drawer-fab")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-drawer-panel")).not.toBeInTheDocument();
  });

  it("offers a link to activate rather than a chat body when never activated", async () => {
    serveConfig(null);
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await userEvent.click(screen.getByTestId("operator-drawer-fab"));

    expect(await screen.findByTestId("operator-drawer-activate-link")).toHaveAttribute(
      "href",
      "/manage/operator",
    );
    expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();
  });

  it("opens to a working chat when the operator is active", async () => {
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await userEvent.click(screen.getByTestId("operator-drawer-fab"));

    expect(await screen.findByTestId("operator-input")).toBeInTheDocument();
    expect(screen.getByTestId("operator-drawer-panel")).toBeInTheDocument();
  });

  it("closes via its own close button", async () => {
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await userEvent.click(screen.getByTestId("operator-drawer-fab"));
    expect(await screen.findByTestId("operator-drawer-panel")).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("operator-drawer-close"));
    await waitFor(() => expect(screen.queryByTestId("operator-drawer-panel")).not.toBeInTheDocument());
  });

  it("shows the compact pause notice, not the full approval banner, when paused", async () => {
    serveConfig(activeConfig());
    // Seeded directly on the shared, public chat state — this test is about
    // the drawer's OWN rendering choice (compact vs banner), not re-deriving
    // pause detection, which the hook's own test suite already covers.
    useOperatorChatStore.setState({
      conversationId: "conv-1",
      isPaused: true,
      pauseReason: "Creating a new agent — review the whole config",
    });
    server.use(
      http.get("*/agents/conv-1/approval-status", () =>
        HttpResponse.json({ pauseReason: "Creating a new agent — review the whole config" }),
      ),
    );

    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await userEvent.click(screen.getByTestId("operator-drawer-fab"));

    const notice = await screen.findByTestId("operator-chat-compact-pause");
    expect(notice).toHaveTextContent(/creating a new agent/i);
    expect(screen.queryByRole("button", { name: /approve/i })).not.toBeInTheDocument();
  });
});
