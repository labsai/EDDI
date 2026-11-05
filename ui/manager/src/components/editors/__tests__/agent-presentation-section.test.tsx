import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { Agent } from "@/lib/api/agents";
import { AgentPresentationSection } from "../agent-presentation-section";

const baseAgent = { workflows: [] } as unknown as Agent;

/** Holds the draft the way the agent page does, and reports every edit. */
function Harness({ initial, onEdit }: { initial: Agent; onEdit: (agent: Agent) => void }) {
  const [agent, setAgent] = useState(initial);
  return (
    <>
      <AgentPresentationSection
        agent={agent}
        onChange={(next) => {
          setAgent(next);
          onEdit(next);
        }}
      />
      <button type="button" data-testid="discard" onClick={() => setAgent(initial)} />
    </>
  );
}

function starters() {
  // The section starts collapsed; open it once.
  if (!screen.queryByTestId("appearance-starters")) {
    fireEvent.click(screen.getByRole("button", { expanded: false }));
  }
  return screen.getByTestId("appearance-starters") as HTMLTextAreaElement;
}

describe("AgentPresentationSection — conversation starters", () => {
  it("never puts a blank line in the draft, but keeps it in the textarea", () => {
    const onEdit = vi.fn();
    render(<Harness initial={baseAgent} onEdit={onEdit} />);

    fireEvent.change(starters(), { target: { value: "Track my order\n" } });

    expect(onEdit).toHaveBeenLastCalledWith(
      expect.objectContaining({ appearance: expect.objectContaining({ starters: ["Track my order"] }) }),
    );
    expect(starters().value).toBe("Track my order\n");
  });

  it("drops whitespace-only lines anywhere", () => {
    const onEdit = vi.fn();
    render(<Harness initial={baseAgent} onEdit={onEdit} />);

    fireEvent.change(starters(), { target: { value: "A\n   \nB\n" } });

    expect(onEdit.mock.lastCall?.[0].appearance.starters).toEqual(["A", "B"]);
  });

  it("shows only what will be saved: each starter cut at 200 characters", () => {
    const onEdit = vi.fn();
    render(<Harness initial={baseAgent} onEdit={onEdit} />);

    fireEvent.change(starters(), { target: { value: `${"x".repeat(250)}\n2\n\n3` } });

    expect(onEdit.mock.lastCall?.[0].appearance.starters).toEqual(["x".repeat(200), "2", "3"]);
    expect(starters().value).toBe(`${"x".repeat(200)}\n2\n\n3`);
  });

  it("refuses a seventh starter without deleting one that is there", () => {
    const six = ["1", "2", "3", "4", "5", "6"];
    const initial = { ...baseAgent, appearance: { starters: six } } as Agent;
    const onEdit = vi.fn();
    render(<Harness initial={initial} onEdit={onEdit} />);

    // A new line inserted before the last starter.
    fireEvent.change(starters(), { target: { value: "1\n2\n3\n4\n5\nnew\n6" } });

    expect(onEdit).not.toHaveBeenCalled();
    expect(starters().value).toBe(six.join("\n"));
  });

  it("follows the stored starters when the draft is discarded", () => {
    const initial = { ...baseAgent, appearance: { starters: ["Hello"] } } as Agent;
    render(<Harness initial={initial} onEdit={vi.fn()} />);
    expect(starters().value).toBe("Hello");

    fireEvent.change(starters(), { target: { value: "Changed\n" } });
    fireEvent.click(screen.getByTestId("discard"));

    expect(starters().value).toBe("Hello");
  });
});
