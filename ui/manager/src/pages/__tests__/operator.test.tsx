import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorPage } from "../operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";

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

function activeConfig(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return {
    ...defaultOperatorConfig("Body."),
    enabled: true,
    agentId: "op-1",
    version: 2,
    ...overrides,
  };
}

/** Serve the operator config variable, or a 404 when there is none. */
function serveConfig(config: OperatorConfig | null) {
  server.use(
    http.get(VAR_URL, () =>
      config
        ? HttpResponse.json({
            key: OPERATOR_VARIABLE_KEY,
            value: JSON.stringify(config),
          })
        : HttpResponse.json({ message: "not found" }, { status: 404 }),
    ),
  );
}

function serveDeploymentStatus(status: string) {
  server.use(
    http.get("*/administration/:env/deploymentstatus/:agentId", () =>
      HttpResponse.json({ status }),
    ),
  );
}

describe("OperatorPage", () => {
  beforeEach(() => {
    // jsdom has no scrollIntoView; the chat auto-scroll effect calls it.
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    // This page mounts the real useOperatorChat, backed by a module-level
    // store — without this, a pause or conversationId left by one test's
    // render leaks into the next and fires unmocked requests against handlers
    // server.resetHandlers() already removed (MSW is configured to hard-error
    // on those, per src/test/setup.ts).
    useOperatorChatStore.getState().reset();
    server.resetHandlers();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  describe("when the operator has never been activated", () => {
    it("offers an empty state rather than an error", async () => {
      serveConfig(null);
      renderWithProviders(<OperatorPage />);
      expect(
        await screen.findByText(/the platform operator is off/i),
      ).toBeInTheDocument();
    });

    it("opens the activation flow from the empty state", async () => {
      serveConfig(null);
      renderWithProviders(<OperatorPage />);
      await userEvent.click(
        await screen.findByRole("button", { name: /activate the platform operator/i }),
      );
      expect(await screen.findByTestId("operator-provider")).toBeInTheDocument();
    });
  });

  describe("when the config cannot be read", () => {
    // Distinct from "never activated": offering activation here would invite
    // provisioning a second operator alongside one that may already be running.
    it("shows an error with a retry instead of the activation flow", async () => {
      server.use(
        http.get(VAR_URL, () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      );
      renderWithProviders(<OperatorPage />);
      expect(await screen.findByTestId("operator-config-retry")).toBeInTheDocument();
      expect(screen.queryByTestId("operator-provider")).not.toBeInTheDocument();
      expect(
        screen.queryByText(/the platform operator is off/i),
      ).not.toBeInTheDocument();
    });
  });

  describe("when the operator is active", () => {
    it("shows the chat, the scope chip and the model", async () => {
      serveConfig(activeConfig({ model: "claude-sonnet-5" }));
      serveDeploymentStatus("READY");
      renderWithProviders(<OperatorPage />);

      expect(await screen.findByTestId("operator-input")).toBeInTheDocument();
      // Default scope is read_write now; the chip must say so.
      expect(screen.getAllByText(/read & write/i).length).toBeGreaterThan(0);
      expect(screen.getByText("claude-sonnet-5")).toBeInTheDocument();
    });

    it("offers starter prompts before any message is sent", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      renderWithProviders(<OperatorPage />);
      expect((await screen.findAllByTestId("operator-starter")).length).toBeGreaterThan(0);
    });

    it("reports a deployed operator", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      renderWithProviders(<OperatorPage />);
      expect(await screen.findByTestId("operator-status-ready")).toBeInTheDocument();
    });

    it("surfaces a deployment ERROR with guidance rather than a bare badge", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("ERROR");
      renderWithProviders(<OperatorPage />);
      expect(await screen.findByTestId("operator-deployment-error")).toBeInTheDocument();
    });

    it("undeploys with the stored version when the kill switch is confirmed", async () => {
      serveConfig(activeConfig({ version: 2, environment: "production" }));
      serveDeploymentStatus("READY");

      let undeployUrl = "";
      server.use(
        http.post("*/administration/:env/undeploy/:agentId", ({ request }) => {
          undeployUrl = request.url;
          return new HttpResponse(null, { status: 200 });
        }),
        http.put(VAR_URL, () => new HttpResponse(null, { status: 204 })),
      );

      renderWithProviders(<OperatorPage />);
      await userEvent.click(await screen.findByTestId("operator-kill-switch"));

      // Deactivation is destructive enough to confirm, not one stray click.
      const confirm = await screen.findByRole("button", { name: /^deactivate$/i });
      await userEvent.click(confirm);

      await waitFor(() => expect(undeployUrl).toContain("/undeploy/op-1"));
      expect(undeployUrl).toContain("version=2");
    });

    it("does not undeploy if the confirmation is dismissed", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      let undeployCalled = false;
      server.use(
        http.post("*/administration/:env/undeploy/:agentId", () => {
          undeployCalled = true;
          return new HttpResponse(null, { status: 200 });
        }),
      );

      renderWithProviders(<OperatorPage />);
      await userEvent.click(await screen.findByTestId("operator-kill-switch"));
      await userEvent.click(await screen.findByRole("button", { name: /cancel/i }));

      expect(undeployCalled).toBe(false);
    });

    it("can reopen the activation flow to reconfigure", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      renderWithProviders(<OperatorPage />);
      await userEvent.click(await screen.findByRole("button", { name: /reconfigure/i }));
      expect(await screen.findByTestId("operator-provider")).toBeInTheDocument();
    });
  });

  describe("when a turn pauses on a gated tool call", () => {
    /** Wires the 409-pause path (`send` rejected because the conversation is
     *  already AWAITING_HUMAN) since it needs no SSE mocking, plus the
     *  approval-status read that supplies `pauseDetails` — the same two reads
     *  `useOperatorChat`/`useApprovalStatus` perform for a real streamed pause. */
    function servePause(calls: unknown[]) {
      server.use(
        http.post("*/agents/op-1/start", () => HttpResponse.json({ location: "/agents/conv-1" })),
        http.post("*/agents/conv-1/stream", () => new HttpResponse(null, { status: 409 })),
        // Deliberately WITHOUT hitlPauseReason/hitlTimeoutPolicy/hitlApprovalTimeout:
        // SimpleConversationMemorySnapshot carries only hitlPausedAt and
        // hitlPauseType. A mock that invented the others hid a real bug — the UI
        // read the reason and timeouts off this response and got undefined in
        // production while the tests passed.
        http.get("*/conversationstore/conversations/simple/conv-1", () =>
          HttpResponse.json({
            conversationState: "AWAITING_HUMAN",
            hitlPausedAt: "2026-08-03T10:00:00Z",
            conversationOutputs: [],
          }),
        ),
        http.get("*/agents/conv-1/approval-status", () =>
          HttpResponse.json({
            conversationId: "conv-1",
            state: "AWAITING_HUMAN",
            pausedAt: "2026-08-03T10:00:00Z",
            pauseReason: "Tool approval required",
            timeoutPolicy: "AUTO_REJECT",
            approvalTimeout: "PT15M",
            pauseDetails: {
              type: "TOOL_CALL",
              calls,
              executedUngatedCalls: [],
              outcomeUnknown: [],
            },
          }),
        ),
      );
    }

    it("renders the backend's resolved-request preview instead of guessing from the tool name", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      servePause([
        {
          callId: "call-1",
          toolName: "createAgent",
          source: "http",
          arguments: '{"name":"foo"}',
          argsTruncated: false,
          gateReason: "http.post:*",
          requestPinned: true,
          requestPreview: {
            method: "POST",
            uri: "https://eddi.example.com/agentstore/agents",
            queryParams: {},
            headers: { "Content-Type": "application/json" },
            body: '{"name":"foo"}',
            bodyTruncated: false,
          },
        },
      ]);

      renderWithProviders(<OperatorPage />);
      await userEvent.type(await screen.findByTestId("operator-input"), "create an agent{enter}");

      expect(await screen.findByTestId("request-preview-call-1")).toBeInTheDocument();
      expect(screen.getByText(/POST https:\/\/eddi\.example\.com\/agentstore\/agents/)).toBeInTheDocument();
      // The honest server-verified preview replaces the client-side guess —
      // both must never render for the same call.
      expect(screen.queryByTestId("tool-endpoint-call-1")).not.toBeInTheDocument();
    });

    it("shows the pause reason and timeout, which only approval-status carries", async () => {
      // The conversation endpoint this surface also reads returns neither. Sourcing
      // them from there yielded undefined: a blank reason and a countdown that
      // never rendered — invisible until a mock stopped inventing the fields.
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      servePause([
        {
          callId: "call-1",
          toolName: "createAgent",
          source: "http",
          arguments: "{}",
          argsTruncated: false,
          gateReason: "http.post:*",
          requestPinned: false,
          requestPreview: null,
        },
      ]);

      renderWithProviders(<OperatorPage />);
      await userEvent.type(await screen.findByTestId("operator-input"), "create an agent{enter}");

      const banner = await screen.findByTestId("approval-banner");
      expect(banner).toHaveTextContent(/Tool approval required/);
      // The timeout-policy chip renders only when a policy other than
      // WAIT_INDEFINITELY actually arrived — unlike the countdown itself, this
      // does not depend on the wall clock.
      expect(banner).toHaveTextContent(/auto.?reject/i);
    });

    it("falls back to the client-side reconstruction when a call carries no preview", async () => {
      serveConfig(activeConfig());
      serveDeploymentStatus("READY");
      server.use(
        http.get("*/openapi", () =>
          HttpResponse.json({
            openapi: "3.1.0",
            paths: { "/agentstore/agents": { post: { operationId: "createAgent" } } },
          }),
        ),
      );
      servePause([
        {
          callId: "call-1",
          toolName: "createAgent",
          source: "http",
          arguments: '{"name":"foo"}',
          argsTruncated: false,
          gateReason: "http.post:*",
          requestPinned: false,
          requestPreview: null,
        },
      ]);

      renderWithProviders(<OperatorPage />);
      await userEvent.type(await screen.findByTestId("operator-input"), "create an agent{enter}");

      expect(await screen.findByTestId("tool-endpoint-call-1")).toBeInTheDocument();
      expect(screen.getByText(/POST \/agentstore\/agents \(reconstructed\)/)).toBeInTheDocument();
      expect(screen.queryByTestId("request-preview-call-1")).not.toBeInTheDocument();
    });
  });

  describe("when the operator is configured but merely switched off", () => {
    it("offers to turn it back on instead of rebuilding it", async () => {
      serveConfig(activeConfig({ enabled: false }));
      renderWithProviders(<OperatorPage />);
      expect(await screen.findByTestId("operator-reactivate")).toBeInTheDocument();
      expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();
    });

    it("redeploys the existing agent rather than provisioning a new one", async () => {
      serveConfig(activeConfig({ enabled: false, version: 2, environment: "production" }));
      let deployUrl = "";
      let setupApiCalled = false;
      server.use(
        http.post("*/administration/:env/deploy/:agentId", ({ request }) => {
          deployUrl = request.url;
          return new HttpResponse(null, { status: 200 });
        }),
        http.post("*/administration/agents/setup-api", () => {
          setupApiCalled = true;
          return HttpResponse.json({}, { status: 201 });
        }),
        http.put(VAR_URL, () => new HttpResponse(null, { status: 204 })),
      );

      renderWithProviders(<OperatorPage />);
      await userEvent.click(await screen.findByTestId("operator-reactivate"));

      await waitFor(() => expect(deployUrl).toContain("/deploy/op-1"));
      expect(deployUrl).toContain("version=2");
      // Rebuilding would orphan resources and force re-entering the model key.
      expect(setupApiCalled).toBe(false);
    });

    it("shows the never-activated empty state when there is no agent at all", async () => {
      serveConfig(null);
      renderWithProviders(<OperatorPage />);
      expect(
        await screen.findByText(/the platform operator is off/i),
      ).toBeInTheDocument();
    });
  });
});
