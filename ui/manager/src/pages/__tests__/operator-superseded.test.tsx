import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorPage } from "../operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import type { ActivationOutcome } from "@/hooks/use-operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";
import { serveTrustedOperator } from "@/test/operator-trust";

/**
 * The superseded-operator banner, on the page.
 *
 * The hook test proves `supersededWarning` is computed. This proves it reaches the
 * SCREEN — the defect being fixed was precisely that nothing on screen said two
 * operators were deployed. `activationError` is not a substitute: it renders
 * inside the activation form, which the success handler closes.
 *
 * `useActivateOperator` is replaced with one that succeeds with a fixed outcome,
 * and the background probes are stubbed, so the test drives exactly the page's
 * own wiring and nothing else.
 */

const outcome = vi.hoisted(() => ({ current: null as unknown, params: null as unknown }));

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

vi.mock("@/hooks/use-operator", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/hooks/use-operator")>();
  return {
    ...actual,
    runPostActivationProbes: vi.fn(() => Promise.resolve()),
    useActivateOperator: () => ({
      isPending: false,
      mutate: (
        params: unknown,
        options?: { onSuccess?: (result: ActivationOutcome) => void },
      ) => {
        outcome.params = params;
        options?.onSuccess?.(outcome.current as ActivationOutcome);
      },
    }),
  };
});

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;

function activeConfig(): OperatorConfig {
  return {
    ...defaultOperatorConfig("Body."),
    enabled: true,
    agentId: "op-old",
    version: 2,
    credentialKey: "operator-llm-key",
    apiBaseUrl: "http://127.0.0.1:7070",
  };
}

function outcomeWith(supersededWarning: string | null): ActivationOutcome {
  return {
    config: { ...activeConfig(), agentId: "op-new", version: 1 },
    gate: { verified: true, checkedVersions: [1] },
    policyVerified: true,
    spec: { raw: {}, paths: {} },
    supersededWarning,
  };
}

async function reconfigure() {
  renderWithProviders(<OperatorPage />);
  await userEvent.click(await screen.findByRole("button", { name: /reconfigure/i }));
  await userEvent.click(await screen.findByTestId("operator-next"));
  // The stored instructions are carried over unchanged, so the form wants a yes.
  await userEvent.click(await screen.findByTestId("operator-stored-prompt-confirm-checkbox"));
  await userEvent.click(await screen.findByTestId("operator-activate"));
}

describe("OperatorPage — superseded operator banner", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useOperatorChatStore.getState().reset();
    server.resetHandlers();
    serveTrustedOperator("op-old");
    serveTrustedOperator("op-new");
    server.use(
      http.get(VAR_URL, () =>
        HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: JSON.stringify(activeConfig()) }),
      ),
      http.get("*/administration/:env/deploymentstatus/:agentId", () => HttpResponse.json({ status: "READY" })),
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  it("shows a persistent banner naming both agents when the old one could not be retired", async () => {
    outcome.current = outcomeWith(
      "The new operator agent (op-new) is live, but the one it replaced (op-old) could not be removed (409). It may still be deployed and answering.",
    );
    await reconfigure();
    const banner = await screen.findByTestId("operator-superseded-warning");
    expect(banner).toHaveTextContent("op-old");
    expect(banner).toHaveTextContent("op-new");
  });

  /**
   * Whether the replaced agent is retired is the page's call, made from the trust
   * checks: a verified operator is retired as always; one that failed them is
   * left exactly as it is, because the id came from an editor-writable variable
   * and the replacement must not become the way to delete somebody's agent.
   */
  it("asks the activation to retire a verified predecessor", async () => {
    outcome.current = outcomeWith(null);
    await reconfigure();
    await waitFor(() => expect(outcome.params).not.toBeNull());
    expect((outcome.params as { retirePredecessor?: boolean }).retirePredecessor).toBe(true);
  });

  it("asks the activation to leave an unverified predecessor in place", async () => {
    serveTrustedOperator("op-old", { marker: false });
    outcome.current = outcomeWith(null);
    renderWithProviders(<OperatorPage />);
    expect(await screen.findByTestId("operator-untrusted")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /reconfigure/i }));
    await userEvent.click(await screen.findByTestId("operator-next"));
    await userEvent.click(await screen.findByTestId("operator-stored-prompt-confirm-checkbox"));
    await userEvent.click(await screen.findByTestId("operator-activate"));
    await waitFor(() => expect(outcome.params).not.toBeNull());
    expect((outcome.params as { retirePredecessor?: boolean }).retirePredecessor).toBe(false);
  });

  it("shows no banner when the retirement succeeded", async () => {
    outcome.current = outcomeWith(null);
    await reconfigure();
    await waitFor(() => expect(screen.queryByTestId("operator-activate")).not.toBeInTheDocument());
    expect(screen.queryByTestId("operator-superseded-warning")).not.toBeInTheDocument();
  });
});
