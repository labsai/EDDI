import { useState } from "react";
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { TaskToolQuotas } from "../llm/task-tool-quotas";
import type { LlmTask } from "../llm/types";

/** Hosts the section with real state so every edit is visible in `latest`. */
function Host({ initial, readOnly, onTask }: { initial: LlmTask; readOnly?: boolean; onTask: (t: LlmTask) => void }) {
  const [task, setTask] = useState(initial);
  return (
    <TaskToolQuotas
      task={task}
      readOnly={readOnly}
      onChange={(t) => {
        setTask(t);
        onTask(t);
      }}
    />
  );
}

const base = { type: "openai" } as unknown as LlmTask;

function setup(initial: LlmTask = base, readOnly = false) {
  const ref: { latest: LlmTask } = { latest: initial };
  render(<Host initial={initial} readOnly={readOnly} onTask={(t) => (ref.latest = t)} />);
  return ref;
}

describe("TaskToolQuotas", () => {
  it("writes nothing until the author adds a rule", () => {
    const ref = setup();
    expect(ref.latest.toolQuotas).toBeUndefined();
    expect(screen.queryByTestId("task-tool-quota-0")).toBeNull();
  });

  it("adds a per-run rule by default and warns that it is not an abuse limit", () => {
    const ref = setup();
    fireEvent.click(screen.getByTestId("task-tool-quotas-add"));

    expect(ref.latest.toolQuotas).toEqual({ tools: { tool: { limit: 20, window: "RUN" } } });
    expect(screen.getByTestId("task-tool-quotas-run-note")).toBeTruthy();
  });

  it("switching to a fixed window drops the per-run note", () => {
    const ref = setup();
    fireEvent.click(screen.getByTestId("task-tool-quotas-add"));
    fireEvent.change(screen.getByTestId("task-tool-quota-window-0"), { target: { value: "P1D" } });

    expect(ref.latest.toolQuotas?.tools.tool?.window).toBe("P1D");
    expect(screen.queryByTestId("task-tool-quotas-run-note")).toBeNull();
  });

  it("keeps a window the picker does not offer instead of rewriting it", () => {
    setup({ ...base, toolQuotas: { tools: { search: { limit: 5, window: "PT90M" } } } });
    const select = screen.getByTestId("task-tool-quota-window-0") as HTMLSelectElement;
    expect(select.value).toBe("PT90M");
  });

  it("removing the last rule removes toolQuotas entirely", () => {
    const ref = setup({ ...base, toolQuotas: { tools: { search: { limit: 5, window: "RUN" } } } });
    fireEvent.click(screen.getAllByRole("button").find((b) => b.getAttribute("aria-label") !== null)!);

    expect(ref.latest.toolQuotas).toBeUndefined();
  });

  it("an emptied subject is dropped, a typed one is kept", () => {
    const ref = setup({ ...base, toolQuotas: { tools: { search: { limit: 5, window: "P1D" } } } });
    const subject = screen.getByTestId("task-tool-quotas-subject");

    fireEvent.change(subject, { target: { value: "{context.personId}" } });
    expect(ref.latest.toolQuotas?.subject).toBe("{context.personId}");

    fireEvent.change(subject, { target: { value: "" } });
    expect(ref.latest.toolQuotas).toEqual({ tools: { search: { limit: 5, window: "P1D" } } });
  });

  it("read-only shows the rules but offers no way to change them", () => {
    setup({ ...base, toolQuotas: { tools: { search: { limit: 5, window: "RUN" } } } }, true);
    expect(screen.getByTestId("task-tool-quota-0")).toBeTruthy();
    expect(screen.queryByTestId("task-tool-quotas-add")).toBeNull();
  });
});
