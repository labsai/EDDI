import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorPage } from "../operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";

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
    it("shows the chat, the read-only chip and the model", async () => {
      serveConfig(activeConfig({ model: "claude-sonnet-4-6" }));
      serveDeploymentStatus("READY");
      renderWithProviders(<OperatorPage />);

      expect(await screen.findByTestId("operator-input")).toBeInTheDocument();
      expect(screen.getAllByText(/read-only/i).length).toBeGreaterThan(0);
      expect(screen.getByText("claude-sonnet-4-6")).toBeInTheDocument();
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

  describe("when the operator is configured but disabled", () => {
    it("shows the empty state, not a broken chat", async () => {
      serveConfig(activeConfig({ enabled: false }));
      renderWithProviders(<OperatorPage />);
      expect(
        await screen.findByText(/the platform operator is off/i),
      ).toBeInTheDocument();
      expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();
    });
  });
});
