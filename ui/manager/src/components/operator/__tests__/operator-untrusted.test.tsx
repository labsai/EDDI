import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorDrawer } from "../operator-drawer";
import { OperatorPage } from "@/pages/operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import { OPERATOR_DESCRIPTOR_DESCRIPTION } from "@/lib/api/operator-marker";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";
import { useOperatorDrawerStore } from "@/hooks/use-operator-drawer";
import { serveTrustedOperator } from "@/test/operator-trust";

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

/**
 * `platform.operator` is writable by `eddi-editor`, and the Manager takes the
 * operator's agent id from it. An editor can repoint it at an agent they wrote;
 * the admin would then chat with that agent and approve what it proposes. These
 * tests pin the Manager's half of the fix: no conversation is opened with an
 * agent that lacks the operator marker or a verified approval gate.
 */

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;

function serveConfig(config: OperatorConfig) {
  server.use(
    http.get(VAR_URL, () =>
      HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: JSON.stringify(config) }),
    ),
  );
}

const config: OperatorConfig = {
  ...defaultOperatorConfig("Body."),
  enabled: true,
  agentId: "op-evil",
  version: 1,
};

/** Every request that would start, read or continue a conversation with the agent. */
function watchConversationTraffic() {
  const hits: string[] = [];
  server.use(
    http.post("*/agents/:agentId/start", ({ params }) => {
      hits.push(`start ${String(params.agentId)}`);
      return new HttpResponse(null, { status: 201 });
    }),
    http.post("*/agents/:conversationId", ({ params }) => {
      hits.push(`say ${String(params.conversationId)}`);
      return HttpResponse.json({});
    }),
    http.get("*/conversationstore/conversations", () => {
      hits.push("list conversations");
      return HttpResponse.json([]);
    }),
  );
  return hits;
}

describe.each([
  ["marker", { marker: false }, "operator-trust-reason-marker"],
  ["gate", { gate: false }, "operator-trust-reason-gate"],
] as const)("an operator agent without a valid %s", (_name, options, reasonTestId) => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useOperatorChatStore.getState().reset();
    useOperatorDrawerStore.setState({ isOpen: false });
    server.resetHandlers();
    serveTrustedOperator("op-evil", options);
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
    serveConfig(config);
  });

  it("shows the needs-attention state in the drawer instead of a chat", async () => {
    const hits = watchConversationTraffic();
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await userEvent.click(await screen.findByTestId("operator-drawer-fab"));

    expect(await screen.findByTestId("operator-untrusted")).toBeInTheDocument();
    expect(screen.getByTestId(reasonTestId)).toBeInTheDocument();
    expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("operator-trust-manage")).toHaveAttribute("href", "/manage/operator");
    // Not even a read of its past conversations.
    expect(hits).toEqual([]);
  });

  it("shows the needs-attention state on the operator page, with no chat or history", async () => {
    const hits = watchConversationTraffic();
    renderWithProviders(<OperatorPage />);

    expect(await screen.findByTestId("operator-untrusted")).toBeInTheDocument();
    expect(screen.getByTestId(reasonTestId)).toBeInTheDocument();
    expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("operator-tab-history")).not.toBeInTheDocument();
    // The status panel stays so the admin can replace or unregister it, but it
    // offers nothing that would probe, deploy, undeploy or delete the agent:
    // the id came from an editor-writable variable, so this may be anyone's agent.
    expect(screen.getByRole("button", { name: /reconfigure/i })).toBeInTheDocument();
    expect(screen.getByTestId("operator-unverified-actions")).toBeInTheDocument();
    expect(screen.getByTestId("operator-unregister")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-reset")).not.toBeInTheDocument();
    expect(screen.queryByTestId("operator-kill-switch")).not.toBeInTheDocument();
    expect(screen.queryByTestId("operator-connection-check")).not.toBeInTheDocument();
    // Nor an upgrade, which is a replacement that would retire it.
    expect(screen.queryByTestId("operator-upgrade-notice")).not.toBeInTheDocument();
    expect(hits).toEqual([]);
  });

  it("unregistering drops the configuration and leaves the agent exactly as it is", async () => {
    const touched: string[] = [];
    server.use(
      http.post("*/administration/:env/undeploy/:agentId", ({ params }) => {
        touched.push(`undeploy ${String(params.agentId)}`);
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete("*/agentstore/agents/:id", ({ params }) => {
        touched.push(`delete ${String(params.id)}`);
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete(VAR_URL, () => {
        touched.push("clear config");
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<OperatorPage />);
    await screen.findByTestId("operator-untrusted");

    await userEvent.click(screen.getByTestId("operator-unregister"));
    await userEvent.click(await screen.findByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(touched).toContain("clear config"));
    expect(touched).toEqual(["clear config"]);
  });

  it("does not redeploy a paused agent that fails the checks, and only unregisters it", async () => {
    const touched: string[] = [];
    server.use(
      http.post("*/administration/:env/deploy/:agentId", ({ params }) => {
        touched.push(`deploy ${String(params.agentId)}`);
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete("*/agentstore/agents/:id", ({ params }) => {
        touched.push(`delete ${String(params.id)}`);
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete(VAR_URL, () => {
        touched.push("clear config");
        return new HttpResponse(null, { status: 204 });
      }),
    );
    serveConfig({ ...config, enabled: false });
    renderWithProviders(<OperatorPage />);

    expect(await screen.findByTestId("operator-untrusted")).toBeInTheDocument();
    expect(screen.getByTestId(reasonTestId)).toBeInTheDocument();
    expect(screen.queryByTestId("operator-reactivate")).not.toBeInTheDocument();
    expect(screen.queryByTestId("operator-reset")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("operator-unregister"));
    await userEvent.click(await screen.findByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(touched).toContain("clear config"));
    expect(touched).toEqual(["clear config"]);
  });
});

/**
 * `config.version` is in the same editor-writable variable as the agent id, so
 * whoever chose the id also chose which version's descriptor would be checked.
 * The marker therefore has to hold at the version the store reports as current —
 * the one a new conversation is served by — not at the recorded one.
 */
describe("the marker is read at the agent's current version", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useOperatorChatStore.getState().reset();
    server.resetHandlers();
    serveTrustedOperator("op-evil", { version: 2 });
    server.use(
      http.get("*/descriptorstore/descriptors/op-evil", ({ request }) =>
        HttpResponse.json({
          resource: `eddi://ai.labs.agent/agentstore/agents/op-evil?version=${new URL(request.url).searchParams.get("version")}`,
          name: "EDDI Platform Operator",
          description:
            new URL(request.url).searchParams.get("version") === "1"
              ? OPERATOR_DESCRIPTOR_DESCRIPTION
              : "Edited since — the marker is gone",
          createdOn: 0,
          lastModifiedOn: 0,
        }),
      ),
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
    // The config records version 1, whose descriptor still carries the marker.
    serveConfig({ ...config, version: 1 });
  });

  it("refuses an agent whose recorded version carries the marker but whose current version does not", async () => {
    renderWithProviders(<OperatorPage />);
    expect(await screen.findByTestId("operator-trust-reason-marker")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();
  });
});

describe("a check that cannot be completed", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useOperatorChatStore.getState().reset();
    server.resetHandlers();
    serveTrustedOperator("op-evil");
    server.use(
      http.get("*/descriptorstore/descriptors/op-evil", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
    serveConfig(config);
  });

  it("fails closed, and a retry that succeeds opens the chat", async () => {
    renderWithProviders(<OperatorPage />);
    expect(await screen.findByTestId("operator-trust-reason-unreadable")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();

    serveTrustedOperator("op-evil");
    await userEvent.click(screen.getByTestId("operator-trust-recheck"));

    expect(await screen.findByTestId("operator-input")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId("operator-untrusted")).not.toBeInTheDocument());
  });
});
