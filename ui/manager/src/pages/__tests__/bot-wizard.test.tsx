import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { BotWizardPage } from "@/pages/bot-wizard";

describe("BotWizardPage", () => {
  it("renders wizard heading", () => {
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });
    expect(screen.getByText("Bot Wizard")).toBeInTheDocument();
  });

  it("renders step progress indicator", () => {
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });
    expect(screen.getByTestId("wizard-steps")).toBeInTheDocument();
  });

  it("renders template selection on step 1", () => {
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });
    expect(screen.getByTestId("template-grid")).toBeInTheDocument();
    expect(screen.getByTestId("template-blank")).toBeInTheDocument();
    expect(screen.getByTestId("template-qa")).toBeInTheDocument();
    expect(screen.getByTestId("template-weather")).toBeInTheDocument();
  });

  it("next button is disabled until template is selected", () => {
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });
    const nextBtn = screen.getByTestId("wizard-next");
    expect(nextBtn).toBeDisabled();
  });

  it("navigates to step 2 after selecting template and clicking Next", async () => {
    const user = userEvent.setup();
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });

    // Select template
    await user.click(screen.getByTestId("template-blank"));

    // Next is now enabled
    const nextBtn = screen.getByTestId("wizard-next");
    expect(nextBtn).not.toBeDisabled();

    await user.click(nextBtn);

    // Step 2: bot name input should appear
    expect(screen.getByTestId("wizard-bot-name")).toBeInTheDocument();
  });

  it("back button navigates to previous step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });

    // Go to step 2
    await user.click(screen.getByTestId("template-blank"));
    await user.click(screen.getByTestId("wizard-next"));
    expect(screen.getByTestId("wizard-bot-name")).toBeInTheDocument();

    // Go back
    await user.click(screen.getByTestId("wizard-back"));
    expect(screen.getByTestId("template-grid")).toBeInTheDocument();
  });

  it("navigates all the way to review step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<BotWizardPage />, {
      initialRoute: "/manage/bots/wizard",
    });

    // Step 1: select template
    await user.click(screen.getByTestId("template-blank"));
    await user.click(screen.getByTestId("wizard-next"));

    // Step 2: enter name
    await user.type(screen.getByTestId("wizard-bot-name"), "Test Bot");
    await user.click(screen.getByTestId("wizard-next"));

    // Step 3: packages
    await user.click(screen.getByTestId("wizard-next"));

    // Step 4: review section
    expect(screen.getByTestId("wizard-review")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-create-only")).toBeInTheDocument();
    expect(screen.getByTestId("wizard-create-deploy")).toBeInTheDocument();
  });
});
