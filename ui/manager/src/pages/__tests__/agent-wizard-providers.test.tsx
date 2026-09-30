import { describe, expect, it } from "vitest";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { AgentWizardPage } from "@/pages/agent-wizard";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

type User = ReturnType<typeof userEvent.setup>;

async function toLlmStep(user: User, mode: "standard" | "api" = "standard") {
  renderWithProviders(<AgentWizardPage />, { initialRoute: "/manage/agents/wizard" });
  await user.click(screen.getByTestId(mode === "api" ? "type-api" : "type-standard"));
  await user.click(screen.getByTestId("wizard-next"));
  await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
  await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
  await user.click(screen.getByTestId("wizard-next"));
  if (mode === "api") {
    await user.click(screen.getByText("Paste"));
    fireEvent.change(screen.getByTestId("wizard-spec-paste"), {
      target: { value: '{"openapi":"3.0.0"}' },
    });
    await user.click(screen.getByTestId("wizard-next"));
  }
}

const created = {
  action: "created",
  agentId: "a-1",
  agentName: "My Agent",
  provider: "ollama",
  model: "llama3.3:70b",
  deployed: false,
  deploymentStatus: null,
};

describe("AgentWizardPage — providers", () => {
  /**
   * gemini-vertex needs projectId and location, which the setup request cannot
   * carry; offered, it produced an agent that failed on its first message.
   */
  it("does not offer a provider the setup endpoint cannot configure", async () => {
    const user = userEvent.setup();
    await toLlmStep(user);
    const options = within(screen.getByTestId("wizard-provider"))
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).not.toContain("gemini-vertex");
    expect(options).toContain("gemini");
    expect(options).toContain("huggingface");
  });

  /** L5: the API request never carried the LLM's own base URL. */
  it("sends the LLM base URL for an API agent", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/administration/agents/setup-api", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...created, endpointCount: 1 });
      }),
    );
    const user = userEvent.setup();
    await toLlmStep(user, "api");

    await user.selectOptions(screen.getByTestId("wizard-provider"), "ollama");
    await user.type(screen.getByTestId("wizard-model"), "llama3.3:70b");
    await user.type(screen.getByTestId("wizard-baseurl"), "http://gpu-box:11434");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-create-only"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.llmBaseUrl).toBe("http://gpu-box:11434");
    // apiBaseUrl is the tool target, a different thing.
    expect(body!.apiBaseUrl).toBeUndefined();
  });

  /** L6: Jlama's Hugging Face token (authToken) had no field in the wizard. */
  it("offers an optional Hugging Face token for Jlama and sends it", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/administration/agents/setup", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...created, provider: "jlama" });
      }),
    );
    const user = userEvent.setup();
    await toLlmStep(user);

    await user.selectOptions(screen.getByTestId("wizard-provider"), "jlama");
    expect(screen.getByText("Hugging Face token")).toBeInTheDocument();
    await user.type(screen.getByTestId("wizard-model"), "tjake/Llama-3.2-1B-Instruct-JQ4");
    // Optional: the step can be left without one…
    expect(screen.getByTestId("wizard-next")).toBeEnabled();
    // …and one typed is sent in the key slot, which the backend maps to authToken.
    await user.type(screen.getByTestId("wizard-apikey-input"), "hf_gated_token");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-create-only"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.apiKey).toBe("hf_gated_token");
  });

  it("does not carry an API key into the Jlama token field", async () => {
    const user = userEvent.setup();
    await toLlmStep(user);
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-anthropic");
    await user.selectOptions(screen.getByTestId("wizard-provider"), "jlama");
    expect(screen.getByTestId("wizard-apikey-input")).toHaveValue("");
  });
});

describe("AgentWizardPage — built-in tool whitelist", () => {
  /**
   * An empty whitelist is "no whitelist" to the backend, i.e. EVERY tool, so
   * deselecting the last chip silently granted all of them.
   */
  it("cannot deselect the last selected tool", async () => {
    const user = userEvent.setup();
    await toLlmStep(user);
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-key");
    await user.click(screen.getByTestId("wizard-next"));

    await user.click(screen.getByTestId("wizard-toggle-tools"));
    await user.click(screen.getByTestId("wizard-tool-mode-specific"));

    const chips = within(screen.getByTestId("wizard-tools-whitelist")).getAllByRole("button");
    // Deselect all but the first.
    for (const chip of chips.slice(1)) await user.click(chip);
    const last = chips[0]!;
    expect(last).toHaveAttribute("aria-pressed", "true");
    expect(last).toBeDisabled();

    await user.click(last);
    // Still in "Select Specific" with that one tool — not flipped back to "All".
    expect(screen.getByTestId("wizard-tool-mode-specific")).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByTestId("wizard-all-tools-info")).not.toBeInTheDocument();
  });
});
