import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorPage } from "../operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import type { ActivateParams } from "@/hooks/use-operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";
import { OPERATOR_REVISION } from "@/lib/operator/operator-revision";
import { endpointsForScope } from "@/lib/operator/tool-scopes";
import { defaultOperatorPromptBody } from "@/lib/operator/system-prompt";

/**
 * The upgrade path, on the page: an operator provisioned by an older Manager is
 * announced, and one click re-runs activation with every setting carried over.
 *
 * `useActivateOperator` is replaced with one that records what it was asked to
 * do and does nothing — what is under test is the page's wiring from banner to
 * activation request, not activation itself (use-operator's own tests cover it).
 */

const calls = vi.hoisted(() => ({ activate: [] as unknown[] }));

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
      mutate: (params: unknown) => {
        calls.activate.push(params);
      },
    }),
  };
});

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;

/** An operator activated before revisions existed — no stamp at all. */
function legacyConfig(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return {
    ...defaultOperatorConfig("An old default body."),
    enabled: true,
    agentId: "op-old",
    version: 2,
    credentialKey: "operator-llm-key",
    apiBaseUrl: "http://127.0.0.1:7070",
    environment: "test",
    ...overrides,
  };
}

function serve(config: OperatorConfig) {
  server.use(
    http.get(VAR_URL, () => HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: JSON.stringify(config) })),
  );
}

describe("OperatorPage — upgrading an out-of-date operator", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    calls.activate = [];
    useOperatorChatStore.getState().reset();
    server.resetHandlers();
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => HttpResponse.json({ status: "READY" })),
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  it("announces the upgrade for an operator from before revisions existed", async () => {
    serve(legacyConfig());
    renderWithProviders(<OperatorPage />);
    const notice = await screen.findByTestId("operator-upgrade-notice");
    expect(notice).toHaveTextContent(String(OPERATOR_REVISION));
  });

  it("says nothing for an operator provisioned at the current revision", async () => {
    serve(
      legacyConfig({
        provisionedRevision: OPERATOR_REVISION,
        provisionedEndpoints: [...endpointsForScope("read_write")],
        promptBodyIsDefault: true,
      }),
    );
    renderWithProviders(<OperatorPage />);
    await screen.findByTestId("operator-tab-chat");
    expect(screen.queryByTestId("operator-upgrade-notice")).not.toBeInTheDocument();
  });

  it("upgrades in one click, carrying every setting and installing the new default", async () => {
    const config = legacyConfig();
    serve(config);
    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-upgrade-start"));
    // A legacy body that differs from today's default: the admin is asked, with
    // the new default preselected.
    expect(await screen.findByTestId("operator-upgrade-use-default")).toBeChecked();
    await userEvent.click(screen.getByRole("button", { name: /upgrade now/i }));

    await waitFor(() => expect(calls.activate).toHaveLength(1));
    const params = calls.activate[0] as ActivateParams;
    expect(params.config).toEqual({ ...config, promptBody: defaultOperatorPromptBody(config.scope) });
    expect(params.apiKey).toBe("${vault:operator-llm-key}");
    expect(params.agentName).toBe("EDDI Platform Operator");
  });

  it("keeps edited instructions when the admin says so", async () => {
    serve(legacyConfig({ promptBody: "My own words.", promptBodyIsDefault: false, provisionedRevision: 0 }));
    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-upgrade-start"));
    expect(await screen.findByTestId("operator-upgrade-keep-current")).toBeChecked();
    await userEvent.click(screen.getByRole("button", { name: /upgrade now/i }));

    await waitFor(() => expect(calls.activate).toHaveLength(1));
    expect((calls.activate[0] as ActivateParams).config.promptBody).toBe("My own words.");
  });

  it("opens the prefilled form when the key was plain text and cannot be reused", async () => {
    serve(legacyConfig({ credentialKey: null }));
    renderWithProviders(<OperatorPage />);
    expect(await screen.findByTestId("operator-upgrade-blocker")).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("operator-upgrade-start"));
    expect(await screen.findByTestId("operator-provider")).toBeInTheDocument();
    expect(calls.activate).toHaveLength(0);
  });
});
