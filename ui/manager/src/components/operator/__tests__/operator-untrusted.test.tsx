import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorDrawer } from "../operator-drawer";
import { OperatorPage } from "@/pages/operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
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
    // The status panel stays, so the admin can still reconfigure or delete it.
    expect(screen.getByRole("button", { name: /reconfigure/i })).toBeInTheDocument();
    expect(hits).toEqual([]);
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
