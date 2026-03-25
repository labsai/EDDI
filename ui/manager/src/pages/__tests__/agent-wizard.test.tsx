import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { AgentWizardPage } from "@/pages/agent-wizard";

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

    // Step 3: LLM — needs API key for default provider (anthropic)
    await user.type(screen.getByTestId("wizard-apikey"), "sk-test-key");
    await user.click(screen.getByTestId("wizard-next"));

    // Step 4: Features — just proceed
    await user.click(screen.getByTestId("wizard-next"));

    // Step 5: Review
    expect(screen.getByTestId("wizard-review")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-create-only")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-create-deploy")).toBeInTheDocument();
  });
});
