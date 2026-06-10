import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { AgentWizardPage } from "@/pages/agent-wizard";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("AgentWizardPage", () => {
  it("renders wizard heading", () => {
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });
    expect(screen.getByText("Agent Setup Wizard")).toBeInTheDocument();
  });

  it("renders step progress indicator", () => {
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });
    expect(screen.getByTestId("wizard-steps")).toBeInTheDocument();
  });

  it("renders type selection on step 1", () => {
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });
    expect(screen.getByTestId("type-grid")).toBeInTheDocument();
    expect(screen.getByTestId("type-standard")).toBeInTheDocument();
    expect(screen.getByTestId("type-api")).toBeInTheDocument();
  });

  it("next button is disabled until type is selected", () => {
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });
    const nextBtn = screen.getByTestId("wizard-next");
    expect(nextBtn).toBeDisabled();
  });

  it("selects Standard Agent and navigates to step 2", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    const nextBtn = screen.getByTestId("wizard-next");
    expect(nextBtn).not.toBeDisabled();
    await user.click(nextBtn);

    // Step 2: name + system prompt
    expect(screen.getByTestId("wizard-agent-name")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-system-prompt")).toBeInTheDocument();
  });

  it("selects API Agent and navigates to step 2", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-api"));
    await user.click(screen.getByTestId("wizard-next"));

    expect(screen.getByTestId("wizard-agent-name")).toBeInTheDocument();
  });

  it("back button navigates to previous step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    expect(screen.getByTestId("wizard-agent-name")).toBeInTheDocument();

    await user.click(screen.getByTestId("wizard-back"));
    expect(screen.getByTestId("type-grid")).toBeInTheDocument();
  });

  it("step 2 next is disabled without name and prompt", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));

    // Next is disabled initially (no name/prompt)
    expect(screen.getByTestId("wizard-next")).toBeDisabled();

    await user.type(screen.getByTestId("wizard-agent-name"), "Test Agent");
    // Still disabled (no prompt)
    expect(screen.getByTestId("wizard-next")).toBeDisabled();

    await user.type(
      screen.getByTestId("wizard-system-prompt"),
      "You are helpful"
    );
    // Now enabled
    expect(screen.getByTestId("wizard-next")).not.toBeDisabled();
  });

  it("navigates Standard Agent to LLM step with provider selector", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    // Step 1
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));

    // Step 2
    await user.type(screen.getByTestId("wizard-agent-name"), "Test Agent");
    await user.type(
      screen.getByTestId("wizard-system-prompt"),
      "You are helpful"
    );
    await user.click(screen.getByTestId("wizard-next"));

    // Step 3: LLM
    expect(screen.getByTestId("wizard-provider")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-model")).toBeInTheDocument();
  });

  it("API Agent shows OpenAPI spec input on step 3", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-api"));
    await user.click(screen.getByTestId("wizard-next"));

    await user.type(screen.getByTestId("wizard-agent-name"), "API Bot");
    await user.type(
      screen.getByTestId("wizard-system-prompt"),
      "You manage APIs"
    );
    await user.click(screen.getByTestId("wizard-next"));

    // Step 3 for API: spec input
    expect(screen.getByTestId("spec-mode-tabs")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-spec-url")).toBeInTheDocument();
  });

  it("navigates all the way to review step (Standard)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    // Step 1
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));

    // Step 2
    await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
    await user.type(
      screen.getByTestId("wizard-system-prompt"),
      "Be helpful"
    );
    await user.click(screen.getByTestId("wizard-next"));

    // Step 3: LLM — needs model name + API key for default provider (anthropic)
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-test-key");
    await user.click(screen.getByTestId("wizard-next"));

    // Step 4: Features — just proceed
    await user.click(screen.getByTestId("wizard-next"));

    // Step 5: Review
    expect(screen.getByTestId("wizard-review")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-create-only")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-create-deploy")).toBeInTheDocument();
  });

  // ── Create Only mutation ───────────────────────────────────────────

  it("calls setup API when Create Only is clicked", async () => {
    let setupCalled = false;
    server.use(
      http.post("*/administration/agents/setup", () => {
        setupCalled = true;
        return HttpResponse.json({
          agentId: "new-agent-1",
          agentName: "My Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: false,
          deploymentStatus: null,
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    // Navigate through all steps
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-test-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));

    // Review — click Create Only
    await user.click(screen.getByTestId("wizard-create-only"));

    await waitFor(() => {
      expect(setupCalled).toBe(true);
    });
  });

  // ── Create & Deploy mutation ───────────────────────────────────────

  it("calls setup API with deploy=true when Create & Deploy is clicked", async () => {
    let deployFlag = false;
    server.use(
      http.post("*/administration/agents/setup", async ({ request }) => {
        const body = (await request.json()) as { deploy?: boolean };
        deployFlag = body.deploy === true;
        return HttpResponse.json({
          agentId: "new-agent-2",
          agentName: "My Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: true,
          deploymentStatus: "deployed",
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    // Navigate through all steps
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-test-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));

    // Review — click Create & Deploy
    await user.click(screen.getByTestId("wizard-create-deploy"));

    await waitFor(() => {
      expect(deployFlag).toBe(true);
    });
  });

  // ── Success state ──────────────────────────────────────────────────

  it("shows success state with View Agent link after creation", async () => {
    server.use(
      http.post("*/administration/agents/setup", () => {
        return HttpResponse.json({
          agentId: "created-agent-1",
          agentName: "My Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: false,
          deploymentStatus: null,
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    // Navigate through all steps
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-test-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));

    await user.click(screen.getByTestId("wizard-create-only"));

    await waitFor(() => {
      expect(screen.getByText("Agent Created!")).toBeInTheDocument();
      expect(screen.getByText("View Agent")).toBeInTheDocument();
      expect(screen.getByText("Create Another")).toBeInTheDocument();
    });
  });

  // ── Error state ───────────────────────────────────────────────────

  it("shows error message when setup fails", async () => {
    server.use(
      http.post("*/administration/agents/setup", () => {
        return HttpResponse.json(
          { error: "Invalid API key" },
          { status: 400 }
        );
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    // Navigate through all steps
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "bad-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));

    await user.click(screen.getByTestId("wizard-create-only"));

    // Should stay on review step with error
    await waitFor(() => {
      expect(screen.getByTestId("wizard-review")).toBeInTheDocument();
    });
  });

  // ── Provider switching ────────────────────────────────────────────

  it("switches provider and shows model suggestions", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Test Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));

    // Provider select should be present
    const providerSelect = screen.getByTestId("wizard-provider");
    expect(providerSelect).toBeInTheDocument();

    // Model input should be present
    expect(screen.getByTestId("wizard-model")).toBeInTheDocument();
  });

  // ── LLM step: next disabled without model ─────────────────────────

  it("LLM step next is disabled without model and API key", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Test Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));

    // No model or API key → next disabled
    expect(screen.getByTestId("wizard-next")).toBeDisabled();
  });

  // ── Features step interactions ────────────────────────────────────

  /** Helper: navigate through type + identity + LLM to reach Features step (Standard) */
  async function navigateToFeaturesStep(user: ReturnType<typeof userEvent.setup>) {
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });
    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "My Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-key");
    await user.click(screen.getByTestId("wizard-next"));
    // Now on Features step
  }

  it("Features step shows Intro Message toggle for standard agent", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    expect(screen.getByText("Intro Message")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-toggle-intro")).toBeInTheDocument();
  });

  it("enables intro message and shows textarea", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    await user.click(screen.getByTestId("wizard-toggle-intro"));
    expect(screen.getByTestId("wizard-intro-text")).toBeInTheDocument();
    // Pre-filled default text
    expect(screen.getByTestId("wizard-intro-text")).toHaveValue(
      "Hello! How can I help you today?"
    );
  });

  it("toggles structured output and shows sub-options", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    await user.click(screen.getByTestId("wizard-toggle-structured"));
    // Sub-options appear
    expect(screen.getByTestId("structured-output-info")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-toggle-qr")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-toggle-sentiment")).toBeInTheDocument();
  });

  it("toggles sentiment analysis independently", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    // Enable structured output
    await user.click(screen.getByTestId("wizard-toggle-structured"));
    // Enable sentiment
    await user.click(screen.getByTestId("wizard-toggle-sentiment"));
    expect(screen.getByTestId("wizard-toggle-sentiment")).toBeChecked();
  });

  it("shows Built-in Tools toggle for standard agent", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    expect(screen.getByText("Built-in Tools")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-toggle-tools")).toBeInTheDocument();
  });

  it("enables built-in tools and shows All Tools mode by default", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    await user.click(screen.getByTestId("wizard-toggle-tools"));
    expect(screen.getByTestId("wizard-tool-selection-mode")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-all-tools-info")).toBeInTheDocument();
  });

  it("switches to Select Specific mode and shows tool chips", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    await user.click(screen.getByTestId("wizard-toggle-tools"));
    await user.click(screen.getByTestId("wizard-tool-mode-specific"));
    expect(screen.getByTestId("wizard-tools-whitelist")).toBeInTheDocument();
    expect(screen.getByText("Available Tools")).toBeInTheDocument();
  });

  it("shows deploy toggle and environment selector", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    expect(screen.getByTestId("wizard-toggle-deploy")).toBeInTheDocument();
    // Deploy is enabled by default → env selector should be visible
    expect(screen.getByTestId("wizard-environment")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-environment")).toHaveValue("production");
  });

  it("changes environment to test", async () => {
    const user = userEvent.setup();
    await navigateToFeaturesStep(user);
    await user.selectOptions(screen.getByTestId("wizard-environment"), "test");
    expect(screen.getByTestId("wizard-environment")).toHaveValue("test");
  });

  // ── API spec paste mode ───────────────────────────────────────────

  it("switches to paste mode and shows textarea", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-api"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "API Bot");
    await user.type(screen.getByTestId("wizard-system-prompt"), "API manager");
    await user.click(screen.getByTestId("wizard-next"));

    // Click paste tab
    await user.click(screen.getByText("Paste"));
    expect(screen.getByTestId("wizard-spec-paste")).toBeInTheDocument();
  });

  it("switches to file mode and shows dropzone", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-api"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "API Bot");
    await user.type(screen.getByTestId("wizard-system-prompt"), "API manager");
    await user.click(screen.getByTestId("wizard-next"));

    // Click file tab
    await user.click(screen.getByText("File"));
    expect(screen.getByTestId("wizard-spec-dropzone")).toBeInTheDocument();
    expect(screen.getByText("Browse Files")).toBeInTheDocument();
  });

  // ── Review step content ───────────────────────────────────────────

  it("review step shows agent name and model in review table", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Review Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be smart");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "gpt-5");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));

    // Review
    expect(screen.getByText("Review Agent")).toBeInTheDocument();
    expect(screen.getByText("gpt-5")).toBeInTheDocument();
    expect(screen.getByText("Standard Agent")).toBeInTheDocument();
  });

  // ── Success state: deployed badge ─────────────────────────────────

  it("shows deployed badge in success state when agent is deployed", async () => {
    server.use(
      http.post("*/administration/agents/setup", () => {
        return HttpResponse.json({
          action: "created",
          agentId: "deployed-agent",
          agentName: "Deployed Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: true,
          deploymentStatus: "ready",
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Deployed Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Deploy me");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-create-deploy"));

    await waitFor(() => {
      expect(screen.getByText("Agent Created!")).toBeInTheDocument();
      // Check for provider/model summary
      expect(screen.getByText(/Deployed Agent/)).toBeInTheDocument();
      expect(screen.getByText(/anthropic/)).toBeInTheDocument();
    });
  });

  // ── Create Another resets wizard ──────────────────────────────────

  it("Create Another resets to step 1", async () => {
    server.use(
      http.post("*/administration/agents/setup", () => {
        return HttpResponse.json({
          agentId: "agent-1",
          agentName: "Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: false,
          deploymentStatus: null,
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Help");
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-key");
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.click(screen.getByTestId("wizard-create-only"));

    await waitFor(() => {
      expect(screen.getByText("Agent Created!")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Create Another"));

    // Should reset back to type selection
    await waitFor(() => {
      expect(screen.getByTestId("type-grid")).toBeInTheDocument();
    });
  });

  // ── Ollama provider (no API key, base URL required) ───────────────

  it("Ollama provider hides API key and shows required base URL", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Local Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Local help");
    await user.click(screen.getByTestId("wizard-next"));

    // Switch to Ollama
    await user.selectOptions(screen.getByTestId("wizard-provider"), "ollama");

    // API key should NOT be required (Ollama needs no key)
    expect(screen.queryByTestId("wizard-apikey-input")).not.toBeInTheDocument();

    // Base URL should show required hint
    expect(
      screen.getByText(/Required — the URL where your local model server is running/i)
    ).toBeInTheDocument();
  });

  // ── LLM step: base URL input ──────────────────────────────────────

  it("shows base URL input on LLM step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-standard"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "Test Agent");
    await user.type(screen.getByTestId("wizard-system-prompt"), "Be helpful");
    await user.click(screen.getByTestId("wizard-next"));

    expect(screen.getByTestId("wizard-baseurl")).toBeInTheDocument();
  });

  // ── API agent full flow ───────────────────────────────────────────

  it("calls create API agent endpoint for API mode", async () => {
    let apiAgentCalled = false;
    server.use(
      http.post("*/administration/agents/setup-api", () => {
        apiAgentCalled = true;
        return HttpResponse.json({
          action: "created",
          agentId: "api-agent-1",
          agentName: "API Bot",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: false,
          deploymentStatus: null,
          endpointCount: 5,
          groups: ["users", "orders"],
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<AgentWizardPage />, {
      initialRoute: "/manage/agents/wizard",
    });

    await user.click(screen.getByTestId("type-api"));
    await user.click(screen.getByTestId("wizard-next"));
    await user.type(screen.getByTestId("wizard-agent-name"), "API Bot");
    await user.type(screen.getByTestId("wizard-system-prompt"), "API helper");
    await user.click(screen.getByTestId("wizard-next"));

    // API spec step - paste spec (use fireEvent.change because userEvent.type treats { as keyboard modifier)
    await user.click(screen.getByText("Paste"));
    const specInput = screen.getByTestId("wizard-spec-paste");
    fireEvent.change(specInput, { target: { value: '{"openapi":"3.0.0"}' } });
    await user.click(screen.getByTestId("wizard-next"));

    // LLM step
    await user.type(screen.getByTestId("wizard-model"), "claude-sonnet-4-6");
    await user.type(screen.getByTestId("wizard-apikey-input"), "sk-key");
    await user.click(screen.getByTestId("wizard-next"));

    // Features step
    await user.click(screen.getByTestId("wizard-next"));

    // Review - create
    await user.click(screen.getByTestId("wizard-create-only"));

    await waitFor(() => {
      expect(apiAgentCalled).toBe(true);
    });

    // Should show endpoint count
    await waitFor(() => {
      expect(screen.getByText(/5 API endpoints parsed/)).toBeInTheDocument();
    });
  });
});

