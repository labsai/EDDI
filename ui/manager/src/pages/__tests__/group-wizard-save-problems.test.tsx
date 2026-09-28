import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWizardPage } from "@/pages/group-wizard";

/**
 * The wizard creates and deploys every new member agent BEFORE it saves the
 * group. A group the backend then refused left those agents deployed for
 * nothing, with a generic error in place of the backend's reason.
 */
describe("GroupWizardPage — a group the backend would refuse creates no agents", () => {
  it("blocks Create for a HUMAN member in a task force, before any agent is set up", async () => {
    let agentsSetUp = 0;
    let groupPosted = false;
    server.use(
      http.post("*/administration/agents/setup", () => {
        agentsSetUp++;
        return HttpResponse.json({ agentId: "a-new", deployed: true });
      }),
      http.post("*/groupstore/groups", () => {
        groupPosted = true;
        return new HttpResponse(null, { status: 201, headers: { Location: "/groupstore/groups/x?version=1" } });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Mixed Task Force");
    await user.click(screen.getByTestId("gw-style-TASK_FORCE"));
    await user.click(screen.getByTestId("group-wizard-next"));

    await user.click(screen.getByTestId("gw-add-member"));
    await user.click(screen.getByTestId("gw-add-member"));
    await waitFor(() => expect(screen.getByTestId("member-card-1")).toBeInTheDocument());
    await user.type(screen.getByTestId("member-name-0"), "Director");
    await user.click(screen.getByTestId("member-type-human-0"));
    await user.type(screen.getByTestId("human-principal-id-0"), "director@acme.com");
    await user.type(screen.getByTestId("member-name-1"), "Agent Two");
    await user.click(screen.getByTestId("group-wizard-next"));

    expect(await screen.findByTestId("wizard-save-problems")).toHaveTextContent(/task-force group/);
    const create = screen.getByTestId("group-wizard-create");
    expect(create).toBeDisabled();
    await user.click(create);
    expect(agentsSetUp).toBe(0);
    expect(groupPosted).toBe(false);
  });
});
