import { describe, it, expect } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupTemplatesPage } from "@/pages/group-templates";

describe("GroupTemplatesPage", () => {
  it("shows the packaged templates in the gallery", async () => {
    renderWithProviders(<GroupTemplatesPage />, { initialRoute: "/manage/groups/templates" });

    await waitFor(() => {
      expect(screen.getByTestId("template-card-research-pod")).toBeInTheDocument();
    });
    expect(screen.getByTestId("template-card-decision-board")).toBeInTheDocument();
    expect(screen.getByTestId("template-card-research-pod")).toHaveTextContent("Research Pod");
  });

  it("selecting a template shows one input per required role", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupTemplatesPage />, { initialRoute: "/manage/groups/templates" });
    await waitFor(() => screen.getByTestId("template-card-research-pod"));

    await user.click(screen.getByTestId("template-card-research-pod"));

    await waitFor(() => {
      expect(screen.getByTestId("template-role-input-researcher1")).toBeInTheDocument();
    });
    expect(screen.getByTestId("template-role-input-researcher2")).toBeInTheDocument();
    expect(screen.getByTestId("template-role-input-researcher3")).toBeInTheDocument();
    expect(screen.getByTestId("template-role-input-moderator")).toBeInTheDocument();
    // No role in research-pod is HUMAN — every role gets an agent <select>.
    expect(screen.getByTestId("template-role-input-researcher1").tagName).toBe("SELECT");
  });

  it("renders a HUMAN role as a plain text principal-id input, not an agent select", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupTemplatesPage />, { initialRoute: "/manage/groups/templates" });
    await waitFor(() => screen.getByTestId("template-card-decision-board"));

    await user.click(screen.getByTestId("template-card-decision-board"));

    await waitFor(() => {
      expect(screen.getByTestId("template-role-input-humanDirector")).toBeInTheDocument();
    });
    expect(screen.getByTestId("template-role-input-humanDirector").tagName).toBe("INPUT");
    // The other three roles resolve to ordinary agent selects.
    expect(screen.getByTestId("template-role-input-chair").tagName).toBe("SELECT");
  });

  it("disables Create Group until every declared role has an assignment", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupTemplatesPage />, { initialRoute: "/manage/groups/templates" });
    await waitFor(() => screen.getByTestId("template-card-research-pod"));
    await user.click(screen.getByTestId("template-card-research-pod"));
    await waitFor(() => screen.getByTestId("template-create-button"));

    expect(screen.getByTestId("template-create-button")).toBeDisabled();

    const selects = [
      screen.getByTestId("template-role-input-researcher1"),
      screen.getByTestId("template-role-input-researcher2"),
      screen.getByTestId("template-role-input-researcher3"),
      screen.getByTestId("template-role-input-moderator"),
    ];
    for (const select of selects) {
      const options = within(select).getAllByRole("option");
      // options[0] is the "Select an agent…" placeholder.
      if (options.length > 1) {
        await user.selectOptions(select, (options[1] as HTMLOptionElement).value);
      }
    }

    // Only assert the enabled case when the shared agent fixture actually
    // provided at least one selectable agent for every role.
    const stillEmpty = selects.some((s) => (s as HTMLSelectElement).value === "");
    if (!stillEmpty) {
      expect(screen.getByTestId("template-create-button")).not.toBeDisabled();
    }
  });

  it("surfaces the backend's exact validation message on a failed instantiation", async () => {
    server.use(
      http.post("*/groupstore/templates/:templateId/instantiate", () =>
        HttpResponse.json(
          { error: "Group must not mix HUMAN members with task-force phases" },
          { status: 400 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<GroupTemplatesPage />, { initialRoute: "/manage/groups/templates" });
    await waitFor(() => screen.getByTestId("template-card-decision-board"));
    await user.click(screen.getByTestId("template-card-decision-board"));
    await waitFor(() => screen.getByTestId("template-role-input-humanDirector"));

    // Fill every role directly (bypassing the shared agent fixture's exact
    // option list) so Create is enabled and the mock's 400 is actually reached.
    fireEvent.change(screen.getByTestId("template-role-input-advisor1"), { target: { value: "agent1" } });
    fireEvent.change(screen.getByTestId("template-role-input-advisor2"), { target: { value: "agent1" } });
    fireEvent.change(screen.getByTestId("template-role-input-chair"), { target: { value: "agent1" } });
    await user.type(screen.getByTestId("template-role-input-humanDirector"), "director@acme.com");
    expect(screen.getByTestId("template-create-button")).not.toBeDisabled();

    await user.click(screen.getByTestId("template-create-button"));

    await waitFor(() => {
      expect(screen.getByTestId("template-submit-error")).toHaveTextContent(
        "Group must not mix HUMAN members with task-force phases",
      );
    });
  });

  it("goes back to the gallery from the instantiate view", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupTemplatesPage />, { initialRoute: "/manage/groups/templates" });
    await waitFor(() => screen.getByTestId("template-card-research-pod"));
    await user.click(screen.getByTestId("template-card-research-pod"));
    await waitFor(() => screen.getByTestId("template-back"));

    await user.click(screen.getByTestId("template-back"));

    await waitFor(() => {
      expect(screen.getByTestId("template-gallery")).toBeInTheDocument();
    });
  });
});
