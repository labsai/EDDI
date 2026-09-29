import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { CreateGroupDialog } from "../create-group-dialog";

/**
 * A config the backend rejects used to reach it anyway, and its specific 400
 * came back as a generic "Something went wrong".
 */
describe("CreateGroupDialog — what the backend would reject is stopped at review", () => {
  it("holds Create back, and says why, for a debate with no sides and no agents", async () => {
    let posted = false;
    server.use(
      http.post("*/groupstore/groups", () => {
        posted = true;
        return new HttpResponse(null, { status: 201, headers: { Location: "/groupstore/groups/x?version=1" } });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<CreateGroupDialog open={true} onClose={vi.fn()} />);

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("group-name-input"), "One-sided Debate");
    await user.click(screen.getByText("Pro/Con Debate"));
    await user.click(screen.getByRole("button", { name: /Next/i }));
    await user.click(screen.getByRole("button", { name: /Add Member/i }));
    await user.click(screen.getByRole("button", { name: /Add Member/i }));
    const names = screen.getAllByPlaceholderText("Agent Name");
    await user.type(names[0]!, "Alpha");
    await user.type(names[1]!, "Beta");
    await user.click(screen.getByRole("button", { name: /Next/i }));

    const problems = screen.getByTestId("dialog-save-problems");
    expect(problems).toHaveTextContent(/role PRO and one with the role CON/);
    expect(problems).toHaveTextContent(/2 members have no agent assigned/);
    const create = screen.getByTestId("create-group-submit");
    expect(create).toBeDisabled();
    await user.click(create);
    expect(posted).toBe(false);
  });
});
