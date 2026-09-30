import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorActivation } from "../operator-activation";
import { defaultOperatorConfig, type OperatorConfig } from "@/lib/api/operator";
import { defaultOperatorPromptBody } from "@/lib/operator/system-prompt";

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
 * Which instructions the activation form starts from.
 *
 * The defect: the form seeded every Reconfigure from the STORED body, which is a
 * snapshot of whatever the default was at first activation — so no prompt
 * improvement ever reached an existing operator.
 */
async function openReview(initial: OperatorConfig) {
  renderWithProviders(
    <OperatorActivation initial={initial} stage="idle" error={null} onActivate={vi.fn()} />,
  );
  await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
  await userEvent.click(screen.getByTestId("operator-next"));
  return (await screen.findByTestId("operator-prompt-body")) as HTMLTextAreaElement;
}

describe("OperatorActivation — instructions", () => {
  beforeEach(() => {
    server.resetHandlers();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  it("starts from TODAY's default when the stored text was an untouched default", async () => {
    const stale = { ...defaultOperatorConfig("An old default."), promptBodyIsDefault: true };
    const body = await openReview(stale);
    expect(body.value).toBe(defaultOperatorPromptBody(stale.scope));
    expect(screen.queryByTestId("operator-prompt-body-reset")).not.toBeInTheDocument();
  });

  it("keeps edited text, and offers a reset to the current default", async () => {
    const edited = { ...defaultOperatorConfig("My own words."), promptBodyIsDefault: false };
    const body = await openReview(edited);
    expect(body.value).toBe("My own words.");
    await userEvent.click(screen.getByTestId("operator-prompt-body-reset"));
    expect(body.value).toBe(defaultOperatorPromptBody(edited.scope));
    expect(screen.queryByTestId("operator-prompt-body-reset")).not.toBeInTheDocument();
  });

  it("keeps a legacy body it cannot classify, with the reset on offer", async () => {
    const body = await openReview(defaultOperatorConfig("Written before the flag existed."));
    expect(body.value).toBe("Written before the flag existed.");
    expect(screen.getByTestId("operator-prompt-body-reset")).toBeInTheDocument();
  });
});
