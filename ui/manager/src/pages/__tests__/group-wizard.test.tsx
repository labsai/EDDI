import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { GroupWizardPage } from "@/pages/group-wizard";

describe("GroupWizardPage", () => {
  it("renders wizard heading and step indicator", () => {
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });
    expect(screen.getByText("Group Setup Wizard")).toBeInTheDocument();
    expect(screen.getByTestId("group-wizard-steps")).toBeInTheDocument();
  });

  it("shows template cards on step 1", () => {
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });
    expect(screen.getByTestId("template-advisory-board")).toBeInTheDocument();
    expect(screen.getByTestId("template-blank")).toBeInTheDocument();
  });

  it("selecting a template advances to config step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));

    // Should see the config step — name input should be pre-filled
    await waitFor(() => {
      expect(screen.getByTestId("gw-name")).toBeInTheDocument();
    });
    expect(
      (screen.getByTestId("gw-name") as HTMLInputElement).value,
    ).toBe("Advisory Board");
  });

  it("config step Next is disabled without name", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Skip template step
    await user.click(screen.getByTestId("template-blank"));

    // Name is empty — Next should be disabled
    expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
  });

  it("config step Next enables after entering name", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");

    expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
  });

  it("members step requires at least 2 members to proceed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Template → Config → Members
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Members step — no members yet, Next should be disabled
    await waitFor(() => {
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    });
  });

  it("advisory board template pre-fills 5 member cards", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));
    // Skip config step
    await user.click(screen.getByTestId("group-wizard-next"));

    // Should show 5 pre-filled member cards
    await waitFor(() => {
      expect(screen.getByTestId("member-card-0")).toBeInTheDocument();
      expect(screen.getByTestId("member-card-4")).toBeInTheDocument();
    });
  });

  it("add member button creates new member card", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Add first member
    await user.click(screen.getByTestId("gw-add-member"));
    await waitFor(() => {
      expect(screen.getByTestId("member-card-0")).toBeInTheDocument();
    });
  });

  it("back button navigates to previous step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    // Now on config step
    expect(screen.getByTestId("gw-name")).toBeInTheDocument();

    // Go back to template step
    await user.click(screen.getByTestId("group-wizard-back"));
    expect(screen.getByTestId("template-blank")).toBeInTheDocument();
  });

  it("review step shows auto-create notice for new agents", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Use advisory board template (5 members all in "new" mode)
    await user.click(screen.getByTestId("template-advisory-board"));

    // Config → Next
    await user.click(screen.getByTestId("group-wizard-next"));

    // Members → Next (5 members, all "new" mode, should pass)
    await user.click(screen.getByTestId("group-wizard-next"));

    // Review step should show the auto-create notice
    await waitFor(() => {
      expect(screen.getByTestId("auto-create-notice")).toBeInTheDocument();
    });
  });
});
