import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { UserConversationsPage } from "@/pages/user-conversations";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderUserConv(embedded = false) {
  return renderWithProviders(
    <UserConversationsPage embedded={embedded} />
  );
}

describe("UserConversationsPage", () => {
  it("renders the page container", () => {
    renderUserConv();
    expect(screen.getByTestId("user-conversations-page")).toBeInTheDocument();
  });

  it("shows the page title when not embedded", () => {
    renderUserConv();
    expect(screen.getByText("User Conversations")).toBeInTheDocument();
  });

  it("shows the subtitle when not embedded", () => {
    renderUserConv();
    expect(
      screen.getByText(/Lookup and manage intent/)
    ).toBeInTheDocument();
  });

  it("shows create binding button when not embedded", () => {
    renderUserConv();
    expect(screen.getByTestId("create-user-conv-btn")).toBeInTheDocument();
    expect(screen.getByText("Create Binding")).toBeInTheDocument();
  });

  it("hides page title when embedded", () => {
    renderUserConv(true);
    expect(screen.queryByText("User Conversations")).not.toBeInTheDocument();
    // But shows embedded create button
    expect(
      screen.getByTestId("create-user-conv-btn-embedded")
    ).toBeInTheDocument();
  });

  it("shows lookup form with intent and userId inputs", () => {
    renderUserConv();
    expect(screen.getByTestId("uc-intent-input")).toBeInTheDocument();
    expect(screen.getByTestId("uc-userid-input")).toBeInTheDocument();
    expect(screen.getByText("Lookup")).toBeInTheDocument();
  });

  it("shows hint to enter both fields when empty", () => {
    renderUserConv();
    expect(
      screen.getByText("Enter both intent and user ID to search")
    ).toBeInTheDocument();
  });

  it("shows result card when both intent and userId are filled", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "booking");
    await user.type(screen.getByTestId("uc-userid-input"), "user1");

    // Wait for debounce + API response
    await waitFor(
      () => {
        expect(screen.getByTestId("uc-result-card")).toBeInTheDocument();
      },
      { timeout: 2000 }
    );
  });

  it("shows binding details in result card", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "booking");
    await user.type(screen.getByTestId("uc-userid-input"), "user1");

    await waitFor(
      () => {
        expect(screen.getByText("Active Binding")).toBeInTheDocument();
        expect(screen.getByText("booking")).toBeInTheDocument();
        expect(screen.getByText("user1")).toBeInTheDocument();
        expect(screen.getByText("agent1")).toBeInTheDocument();
        expect(screen.getByText("production")).toBeInTheDocument();
        expect(screen.getByText("conv1")).toBeInTheDocument();
      },
      { timeout: 2000 }
    );
  });

  it("shows delete button in result card", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "booking");
    await user.type(screen.getByTestId("uc-userid-input"), "user1");

    await waitFor(
      () => {
        expect(screen.getByTestId("uc-delete-btn")).toBeInTheDocument();
      },
      { timeout: 2000 }
    );
  });

  it("shows delete confirmation dialog when clicking delete", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "booking");
    await user.type(screen.getByTestId("uc-userid-input"), "user1");

    await waitFor(
      () => {
        expect(screen.getByTestId("uc-delete-btn")).toBeInTheDocument();
      },
      { timeout: 2000 }
    );

    await user.click(screen.getByTestId("uc-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText("Delete Binding")).toBeInTheDocument();
    });
  });

  it("shows create dialog when clicking create button", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-user-conv-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("uc-create-dialog")).toBeInTheDocument();
      expect(screen.getByText("Create Binding", { selector: "h2" })).toBeInTheDocument();
    });
  });

  it("create dialog has all required fields", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-user-conv-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("uc-create-dialog")).toBeInTheDocument();
    });

    // Check labels
    expect(screen.getAllByText("Intent").length).toBeGreaterThan(0);
    expect(screen.getAllByText("User ID").length).toBeGreaterThan(0);
    expect(screen.getByText("Agent ID")).toBeInTheDocument();
    expect(screen.getByText("Conversation ID")).toBeInTheDocument();
    expect(screen.getAllByText("Environment").length).toBeGreaterThan(0);
  });

  it("create dialog save button is disabled when fields empty", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-user-conv-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("uc-save-btn")).toBeInTheDocument();
    });

    expect(screen.getByTestId("uc-save-btn")).toBeDisabled();
  });

  it("create dialog closes with cancel button", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-user-conv-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("uc-create-dialog")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(
        screen.queryByTestId("uc-create-dialog")
      ).not.toBeInTheDocument();
    });
  });

  it("does not show result card when only intent is filled", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "booking");

    // Should still show the hint
    expect(
      screen.getByText("Enter both intent and user ID to search")
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("uc-result-card")
    ).not.toBeInTheDocument();
  });

  it("shows 404 not found when API returns 404", async () => {
    server.use(
      http.get(
        "*/userconversationstore/userconversations/:intent/:userId",
        () => {
          return new HttpResponse(null, { status: 404 });
        }
      )
    );

    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "unknown");
    await user.type(screen.getByTestId("uc-userid-input"), "nobody");

    await waitFor(
      () => {
        expect(
          screen.getByText("No binding found for this intent + user")
        ).toBeInTheDocument();
      },
      { timeout: 2000 }
    );
  });

  it("shows conversation ID link that navigates to conversation detail", async () => {
    renderUserConv();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("uc-intent-input"), "booking");
    await user.type(screen.getByTestId("uc-userid-input"), "user1");

    await waitFor(
      () => {
        const link = screen.getByText("conv1");
        expect(link).toBeInTheDocument();
        expect(link.closest("a")?.getAttribute("href")).toBe(
          "/manage/conversationview/conv1"
        );
      },
      { timeout: 2000 }
    );
  });
});
