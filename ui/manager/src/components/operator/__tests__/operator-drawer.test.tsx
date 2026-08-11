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

const authState = vi.hoisted(() => ({ roles: [] as string[], method: "none" as "none" | "keycloak" }));

vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: authState.roles,
    method: authState.method,
    login: () => {},
    logout: () => {},
  }),
  // Mirrors the real implementation: every role is granted when auth is off.
  useHasRole: (role: string) => authState.method === "none" || authState.roles.includes(role),
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
    authState.roles = [];
    authState.method = "none";
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

  describe("when the signed-in user cannot read the operator config", () => {
    // The config lives in the global variable store, which the backend limits
    // to eddi-admin/eddi-editor. This component mounts on EVERY page of both
    // shells, so an ungated read is a 403 per navigation for every other role
    // — eddi-approver most of all, since approving is that role's entire job.
    it("renders no launcher at all for a role that lacks both", async () => {
      authState.method = "keycloak";
      authState.roles = ["eddi-approver"];
      let requested = false;
      server.use(
        http.get(VAR_URL, () => {
          requested = true;
          return HttpResponse.json({ message: "forbidden" }, { status: 403 });
        }),
      );

      renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/approvals" });

      expect(screen.queryByTestId("operator-drawer-fab")).not.toBeInTheDocument();
      // Not merely hidden — the privileged request must never be issued.
      await waitFor(() => expect(requested).toBe(false));
    });

    it("still renders for an editor, who is allowed to read it", async () => {
      authState.method = "keycloak";
      authState.roles = ["eddi-editor"];
      serveConfig(activeConfig());
      renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
      expect(await screen.findByTestId("operator-drawer-fab")).toBeInTheDocument();
    });

    it("renders nothing rather than an unusable activation CTA when the read 403s anyway", async () => {
      // Belt-and-braces for a deployment whose roles are mapped differently
      // than the check above assumes: a failed read means we cannot know
      // whether an operator already exists, and inviting the user to set up a
      // second one is the worst available guess.
      authState.method = "keycloak";
      authState.roles = ["eddi-admin"];
      server.use(http.get(VAR_URL, () => HttpResponse.json({ message: "forbidden" }, { status: 403 })));

      renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });

      // The launcher renders optimistically while the read is in flight, so
      // this waits for it to be withdrawn once the 403 lands — asserting on
      // the panel's contents instead would pass trivially, the panel being
      // closed either way.
      await waitFor(() => expect(screen.queryByTestId("operator-drawer-fab")).not.toBeInTheDocument());
      expect(screen.queryByTestId("operator-drawer-activate-link")).not.toBeInTheDocument();
    });
  });

  it("anchors the panel to the launcher, not to a corner of the viewport", async () => {
    // This replaces a test that pinned the old `bottom-24` / `bottom-40`
    // offsets. Those numbers existed to dodge whatever else occupied the
    // bottom-right corner — sonner's toast viewport, ChatDrawer's composer,
    // WorkforceBottomTabs, workforce-dashboard's own MobileFab (which won the
    // hit test and navigated to /workforce/new when tapped). Living in the
    // header removes the whole class of collision, so what matters now is that
    // the panel positions relative to its launcher and never reintroduces a
    // viewport-fixed offset of its own.
    serveConfig(activeConfig());
    const { container } = renderWithProviders(<OperatorDrawer />, {
      initialRoute: "/manage/agents",
    });
    await userEvent.click(screen.getByTestId("operator-drawer-fab"));

    const panel = await screen.findByTestId("operator-drawer-panel");
    expect(panel.className).toContain("absolute");
    expect(panel.className).not.toContain("fixed");
    expect(container.querySelector('[class*="bottom-"]')).not.toBeInTheDocument();
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

describe("OperatorDrawer — keyboard and pending-approval affordances", () => {
  beforeEach(() => {
    authState.roles = [];
    authState.method = "none";
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
      http.get("*/pending-approvals", () => HttpResponse.json([])),
    );
  });

  it("closes on Escape", async () => {
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await userEvent.click(screen.getByTestId("operator-drawer-fab"));
    expect(await screen.findByTestId("operator-drawer-panel")).toBeInTheDocument();

    await userEvent.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByTestId("operator-drawer-panel")).not.toBeInTheDocument(),
    );
  });

  it("returns focus to the launcher when it closes", async () => {
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    const fab = screen.getByTestId("operator-drawer-fab");
    await userEvent.click(fab);
    await screen.findByTestId("operator-drawer-panel");

    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(fab).toHaveFocus());
  });

  it("does not steal focus to the launcher on first mount", async () => {
    // The restore is for a real open→close transition; without the guard every
    // page load would yank focus to the launcher.
    serveConfig(activeConfig());
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await waitFor(() => expect(screen.getByTestId("operator-drawer-fab")).toBeInTheDocument());
    expect(screen.getByTestId("operator-drawer-fab")).not.toHaveFocus();
  });

  it("marks the launcher when a decision is waiting, from SERVER state", async () => {
    // isPaused is only ever set by a turn THIS tab streamed, so after a reload
    // — or a pause raised elsewhere — the launcher would look idle while the
    // operator sat blocked.
    serveConfig(activeConfig());
    server.use(
      http.get("*/pending-approvals", () =>
        HttpResponse.json([
          { conversationId: "other-conv", agentId: "op-1", pauseType: "TOOL_CALL", pausedAt: null },
        ]),
      ),
    );

    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });

    expect(await screen.findByTestId("operator-drawer-pending-dot")).toBeInTheDocument();
    expect(screen.getByTestId("operator-drawer-fab")).toHaveAccessibleName(/waiting on you/i);
  });

  it("leaves the launcher unmarked when the pending approval belongs to another agent", async () => {
    serveConfig(activeConfig());
    server.use(
      http.get("*/pending-approvals", () =>
        HttpResponse.json([
          { conversationId: "c9", agentId: "some-other-agent", pauseType: "RULE", pausedAt: null },
        ]),
      ),
    );

    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await waitFor(() => expect(screen.getByTestId("operator-drawer-fab")).toBeInTheDocument());
    expect(screen.queryByTestId("operator-drawer-pending-dot")).not.toBeInTheDocument();
  });
});
