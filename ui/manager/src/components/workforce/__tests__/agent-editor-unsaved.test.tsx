import { useState } from "react";
import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";

import { AgentEditorSheet } from "../agent-editor-sheet";

/**
 * The sheet has four ways out — Escape, the backdrop, the X, and Cancel — and
 * they did not agree with each other.
 *
 * Escape and the backdrop called `window.confirm`. The X and Cancel called
 * `onClose` directly, so typing into an agent's description and then clicking
 * the X threw the edit away with no warning at all. A guard present on two of
 * four paths is the sort of thing an existing "renders close button that calls
 * onClose" test actively certifies as correct.
 *
 * So every path gets the same three questions: does a clean sheet close without
 * fuss, does a dirty one refuse to close until asked, and does declining keep
 * the edit.
 */

async function openSheet(onClose: () => void, { dirty }: { dirty: boolean }) {
  const user = userEvent.setup();
  renderWithProviders(<AgentEditorSheet agentId="agent1" onClose={onClose} />);
  const description = await screen.findByLabelText("Description");
  if (dirty) await user.type(description, " edited");
  return user;
}

/** The four exits. Each runs while no dialog is open, so the queries are unambiguous. */
const EXITS: Array<[string, (user: ReturnType<typeof userEvent.setup>) => Promise<void>]> = [
  ["the X", (user) => user.click(screen.getByTestId("agent-editor-close"))],
  ["Cancel", (user) => user.click(screen.getByTestId("agent-editor-cancel"))],
  ["Escape", (user) => user.keyboard("{Escape}")],
  ["the backdrop", (user) => user.click(screen.getByTestId("agent-editor-backdrop"))],
];

describe("AgentEditorSheet unsaved changes", () => {
  describe.each(EXITS)("closing via %s", (_label, exit) => {
    it("closes straight away when nothing has been edited", async () => {
      const onClose = vi.fn();
      const user = await openSheet(onClose, { dirty: false });

      await exit(user);

      await waitFor(() => expect(onClose).toHaveBeenCalled());
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });

    it("asks first when there are unsaved changes", async () => {
      const onClose = vi.fn();
      const user = await openSheet(onClose, { dirty: true });

      await exit(user);

      expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
      expect(onClose).not.toHaveBeenCalled();
    });

    it("keeps the edit when the discard is declined", async () => {
      const onClose = vi.fn();
      const user = await openSheet(onClose, { dirty: true });

      await exit(user);
      await screen.findByRole("alertdialog");
      await user.click(screen.getByTestId("unsaved-cancel"));

      await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
      expect(onClose).not.toHaveBeenCalled();
      const description = screen.getByLabelText("Description") as HTMLTextAreaElement;
      expect(description.value).toContain("edited");
    });
  });

  it("closes once the discard is confirmed", async () => {
    const onClose = vi.fn();
    const user = await openSheet(onClose, { dirty: true });

    await user.keyboard("{Escape}");
    await screen.findByRole("alertdialog");
    await user.click(screen.getByTestId("unsaved-confirm"));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  /**
   * Both call sites — workforce-analytics.tsx and workforce-thread.tsx — keep
   * this component permanently mounted and only flip `agentId`:
   *
   *   <AgentEditorSheet agentId={editingAgentId} onClose={() => setEditingAgentId(null)} />
   *
   * `if (!agentId) return null` renders nothing but does NOT unmount, so any
   * state left set survives into the next agent's sheet. This harness mirrors
   * that rather than remounting, which is what the tests above do.
   */
  it("does not carry the discard dialog into the next agent's sheet", async () => {
    function Parent() {
      const [editingAgentId, setEditingAgentId] = useState<string | null>("agent1");
      return (
        <>
          <button onClick={() => setEditingAgentId("agent2")}>edit the next one</button>
          <AgentEditorSheet
            agentId={editingAgentId}
            onClose={() => setEditingAgentId(null)}
          />
        </>
      );
    }

    const user = userEvent.setup();
    renderWithProviders(<Parent />);

    await user.type(await screen.findByLabelText("Description"), " edited");
    await user.keyboard("{Escape}");
    await user.click(await screen.findByTestId("unsaved-confirm"));

    // The sheet is closed. Now open a different agent.
    await waitFor(() => expect(screen.queryByLabelText("Description")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "edit the next one" }));

    await screen.findByLabelText("Description");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  /**
   * The case above walks agent1 -> null -> agent2, because confirming a discard
   * calls onClose(). Both production call sites do the same. This one flips the
   * id DIRECTLY while the dialog is open, which is what actually pins the reset
   * in the [agentId] effect rather than the one in onConfirm.
   */
  it("drops the discard dialog when the id changes underneath it", async () => {
    function Parent() {
      const [agentId, setAgentId] = useState<string | null>("agent1");
      return (
        <>
          <button onClick={() => setAgentId("agent2")}>switch agent</button>
          <AgentEditorSheet agentId={agentId} onClose={() => setAgentId(null)} />
        </>
      );
    }

    const user = userEvent.setup();
    renderWithProviders(<Parent />);

    await user.type(await screen.findByLabelText("Description"), " edited");
    await user.keyboard("{Escape}");
    await screen.findByRole("alertdialog");

    await user.click(screen.getByRole("button", { name: "switch agent" }));

    await waitFor(() =>
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
    );
  });

  /**
   * `description` and `capabilities` are synced from `agent` by an effect, so they
   * self-correct when the sheet changes agent. The capability DRAFT is not synced
   * by anything — a half-typed skill stayed on screen for the next agent, and Add
   * would have written agent A's skill onto agent B. A half-typed draft also does
   * not make the sheet dirty, so this leaks through an ordinary clean close.
   */
  it("does not carry a half-typed capability to the next agent", async () => {
    function Parent() {
      const [agentId, setAgentId] = useState<string | null>("agent1");
      return (
        <>
          <button onClick={() => setAgentId("agent2")}>switch agent</button>
          <AgentEditorSheet agentId={agentId} onClose={() => setAgentId(null)} />
        </>
      );
    }

    const user = userEvent.setup();
    renderWithProviders(<Parent />);

    await screen.findByLabelText("Description");
    await user.click(screen.getByRole("button", { name: /Add Capability/i }));
    await user.type(await screen.findByLabelText("Skill"), "agent1-only-skill");

    await user.click(screen.getByRole("button", { name: "switch agent" }));

    await screen.findByLabelText("Description");
    expect(screen.queryByLabelText("Skill")).not.toBeInTheDocument();
  });

  /**
   * Reload and tab-close are the fifth exit, and only the browser can ask about
   * them. Asserting the event is cancelled is the whole of what the guard does.
   */
  it("blocks a reload only while there are unsaved changes", async () => {
    const fire = () => {
      const e = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(e);
      return e.defaultPrevented;
    };

    const user = userEvent.setup();
    renderWithProviders(<AgentEditorSheet agentId="agent1" onClose={vi.fn()} />);

    await screen.findByLabelText("Description");
    expect(fire()).toBe(false);

    await user.type(screen.getByLabelText("Description"), " edited");
    expect(fire()).toBe(true);
  });

  /**
   * The sheet listens for Escape on `document`, UnsavedChangesDialog on `window`,
   * and both fire for one keypress: the sheet reopens what the dialog just closed.
   * Today that resolves correctly only because `document` bubbles first, so the
   * sheet's `if (discardOpen) return;` cannot be pinned by any test — removing it
   * leaves this green. Checked, rather than assumed.
   *
   * What this does pin is the behaviour itself, which must hold however the
   * listeners are arranged: Escape closes the dialog, keeps the sheet open, and
   * keeps the edit. If someone moves a listener or adds a stopPropagation and the
   * ordering stops saving us, this is the test that goes red.
   */
  it("lets Escape close the discard dialog without reopening it", async () => {
    const onClose = vi.fn();
    const user = await openSheet(onClose, { dirty: true });

    await user.keyboard("{Escape}");
    await screen.findByRole("alertdialog");

    await user.keyboard("{Escape}");

    await waitFor(() =>
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
    );
    expect(onClose).not.toHaveBeenCalled();
    expect((screen.getByLabelText("Description") as HTMLTextAreaElement).value).toContain("edited");
  });
});
