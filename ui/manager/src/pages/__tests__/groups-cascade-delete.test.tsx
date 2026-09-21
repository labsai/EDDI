import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupsPage } from "@/pages/groups";

/**
 * The list's trash icon is the entry point most people use, and it used to
 * delete the group alone — leaving a wizard-built team's agents belonging to
 * nothing. The checkbox that fixes that has to cascade over the group's REAL
 * configuration: the enriched descriptor behind this list carries a member list
 * but no `moderatorAgentId`, and `deleteGroupWithMembers` deletes the moderator
 * too, so cascading over the descriptor leaves exactly the orphan the checkbox
 * exists to prevent.
 */

const GROUP = {
  id: "grp-cascade",
  name: "Standing team",
  description: "",
  style: "ROUND_TABLE",
  moderatorAgentId: "agent-moderator",
  members: [
    { agentId: "agent-one", displayName: "Ana" },
    { agentId: "agent-two", displayName: "Ben" },
  ],
};

function serve(
  onDeleteAgent: (id: string) => void,
  groupOk = true,
  onDeleteGroup: () => void = () => {},
) {
  server.use(
    http.get("*/groupstore/groups/descriptors", () =>
      HttpResponse.json([
        {
          resource: `eddi://ai.labs.group/groupstore/groups/${GROUP.id}?version=1`,
          name: GROUP.name,
          description: "",
          createdOn: 1,
          lastModifiedOn: 2,
        },
      ]),
    ),
    http.get(`*/groupstore/groups/${GROUP.id}`, () =>
      groupOk
        ? HttpResponse.json(GROUP)
        : new HttpResponse(null, { status: 500 }),
    ),
    http.get("*/agentstore/agents/:agentId/currentversion", () =>
      HttpResponse.text("1"),
    ),
    http.delete("*/agentstore/agents/:agentId", ({ params }) => {
      onDeleteAgent(String(params.agentId));
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete(`*/groupstore/groups/${GROUP.id}`, () => {
      onDeleteGroup();
      return new HttpResponse(null, { status: 204 });
    }),
  );
}

/** Open the card's actions menu and choose Delete. */
async function reopenDeleteDialog() {
  await userEvent.click(await screen.findByTestId(`group-menu-${GROUP.id}`));
  await userEvent.click(await screen.findByRole("button", { name: /^delete$/i }));
  return screen.findByTestId("delete-members-checkbox");
}

async function openDeleteDialog() {
  renderPage("/manage/groups", <GroupsPage />);
  // Delete lives in the card's actions menu, so the menu opens first.
  return reopenDeleteDialog();
}

/**
 * The dialog's confirm button. Found by test id rather than by name: the label
 * becomes "…" while the dialog reports itself pending, which is exactly the
 * state some of these tests assert on.
 */
function confirmButton() {
  return screen.getByTestId("alert-dialog-confirm");
}

describe("GroupsPage — cascade delete", () => {
  it("deletes the moderator too, which the list's own data does not name", async () => {
    const deleted: string[] = [];
    serve((id) => deleted.push(id));

    const checkbox = await openDeleteDialog();
    await userEvent.click(checkbox);
    await userEvent.click(confirmButton());

    await waitFor(() => expect(deleted).toContain("agent-moderator"));
    expect(deleted).toEqual(
      expect.arrayContaining(["agent-one", "agent-two", "agent-moderator"]),
    );
  });

  it("deletes no agents when the box is left unticked", async () => {
    const deleted: string[] = [];
    serve((id) => deleted.push(id));

    await openDeleteDialog();
    await userEvent.click(confirmButton());

    await waitFor(() => expect(screen.queryByTestId("delete-members-checkbox")).toBeNull());
    expect(deleted).toEqual([]);
  });

  it("deletes nothing when the dialog is dismissed while it reads the members", async () => {
    // A cascade awaits `getGroup()` before it can mutate, and the dialog stays
    // dismissable throughout — Escape and the scrim close it whatever
    // `isPending` says. Without a cancellation token the awaited continuation
    // resumes against a dialog nobody is looking at and deletes anyway.
    const deleted: string[] = [];
    let groupDeleted = false;
    let release: (() => void) | null = null;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    serve((id) => deleted.push(id), true, () => {
      groupDeleted = true;
    });
    // The list enriches each descriptor through this same endpoint, so only the
    // confirm-time read is held — holding the first would stop the page
    // rendering at all.
    let reads = 0;
    server.use(
      http.get(`*/groupstore/groups/${GROUP.id}`, async () => {
        if (++reads > 1) await held;
        return HttpResponse.json(GROUP);
      }),
    );

    const checkbox = await openDeleteDialog();
    await userEvent.click(checkbox);
    await userEvent.click(confirmButton());

    // Dismiss while the read is still in flight, then let it land.
    await userEvent.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByTestId("delete-members-checkbox")).toBeNull(),
    );
    release!();

    // The held read now resolves. `waitFor` cannot assert that nothing follows
    // — it succeeds on its first attempt, before the continuation has had a
    // turn — so wait for the read itself to land and then give the continuation
    // real time to do the wrong thing.
    await waitFor(() => expect(reads).toBe(2));
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(deleted).toEqual([]);
    expect(groupDeleted).toBe(false);
  });

  it("a dismissed read does not report a newer one as idle", async () => {
    // Request A is dismissed, request B starts, then A resolves. If A's
    // continuation clears the pending flag, B's dialog looks ready while it is
    // still reading, and its confirm button re-enables mid-flight.
    const deleted: string[] = [];
    const gates: Array<() => void> = [];
    serve((id) => deleted.push(id));
    let reads = 0;
    server.use(
      http.get(`*/groupstore/groups/${GROUP.id}`, async () => {
        if (++reads > 1) {
          await new Promise<void>((resolve) => gates.push(resolve));
        }
        return HttpResponse.json(GROUP);
      }),
    );

    // A
    const checkbox = await openDeleteDialog();
    await userEvent.click(checkbox);
    await userEvent.click(confirmButton());
    await waitFor(() => expect(reads).toBe(2));
    await userEvent.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByTestId("delete-members-checkbox")).toBeNull(),
    );

    // B
    await userEvent.click(await reopenDeleteDialog());
    await userEvent.click(confirmButton());
    await waitFor(() => expect(reads).toBe(3));

    // A lands late. B is still reading, so its confirm must stay busy.
    gates[0]!();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(confirmButton()).toBeDisabled();

    // B lands and deletes exactly once.
    gates[1]!();
    await waitFor(() => expect(deleted).toContain("agent-moderator"));
    expect(deleted.filter((id) => id === "agent-moderator")).toHaveLength(1);
  });

  it("deletes nothing when the group's members cannot be read", async () => {
    // Quietly downgrading to a group-only delete would keep the agents, which
    // is the one thing the reader just said they did not want.
    const deleted: string[] = [];
    serve((id) => deleted.push(id), false);

    const checkbox = await openDeleteDialog();
    await userEvent.click(checkbox);
    await userEvent.click(confirmButton());

    await waitFor(() => expect(screen.getByTestId("delete-members-checkbox")).toBeInTheDocument());
    expect(deleted).toEqual([]);
  });
});
