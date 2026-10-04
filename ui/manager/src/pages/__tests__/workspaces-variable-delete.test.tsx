import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkspacesPage } from "@/pages/workspaces";

/**
 * Deleting a space secret asked first; deleting a variable did not, although an
 * agent that refers to it stops resolving it just the same.
 */
describe("WorkspacesPage — deleting a variable", () => {
  it("asks for confirmation, and keeps the variable on cancel", async () => {
    server.use(
      http.get("*/workspaces", () =>
        HttpResponse.json({
          enabled: true,
          spaces: [{ id: "personal", kind: "personal", label: "alice" }],
          seesEverything: false,
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<WorkspacesPage />, { initialRoute: "/manage/workspaces" });

    await user.type(await screen.findByTestId("space-variable-name"), "model-delete-test");
    await user.type(screen.getByTestId("space-variable-value"), "gpt");
    await user.click(screen.getByTestId("space-variable-save"));
    const row = await screen.findByTestId("space-variable-model-delete-test");

    await user.click(screen.getByRole("button", { name: "Delete model-delete-test" }));
    expect(await screen.findByRole("dialog")).toHaveTextContent("Delete this variable?");
    // Nothing has been deleted yet.
    expect(row).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByTestId("space-variable-model-delete-test")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete model-delete-test" }));
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await waitFor(() =>
      expect(screen.queryByTestId("space-variable-model-delete-test")).not.toBeInTheDocument(),
    );
  });
});
