import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { TaskPricingSection } from "../task-pricing-section";
import type { LlmTask } from "../types";

function Harness({ initial, onChangeSpy }: { initial: LlmTask; onChangeSpy?: (t: LlmTask) => void }) {
  const [task, setTask] = useState<LlmTask>(initial);
  return (
    <TaskPricingSection
      task={task}
      onChange={(t) => {
        setTask(t);
        onChangeSpy?.(t);
      }}
    />
  );
}

describe("TaskPricingSection", () => {
  it("is open by default (unlike Model Cascade, which starts collapsed when off)", () => {
    renderWithProviders(<Harness initial={{ type: "openai", parameters: {} } as LlmTask} />);
    expect(screen.getByTestId("task-pricing-section")).toBeInTheDocument();
    expect(screen.getByTestId("task-input-price")).toBeInTheDocument();
  });

  it("shows blank inputs when no price is set, not 0", () => {
    renderWithProviders(<Harness initial={{ type: "openai", parameters: {} } as LlmTask} />);
    expect(screen.getByTestId("task-input-price")).toHaveValue(null);
    expect(screen.getByTestId("task-output-price")).toHaveValue(null);
  });

  it("writes a typed input price onto the task", () => {
    const onChangeSpy = vi.fn();
    renderWithProviders(
      <Harness initial={{ type: "openai", parameters: {} } as LlmTask} onChangeSpy={onChangeSpy} />,
    );

    fireEvent.change(screen.getByTestId("task-input-price"), { target: { value: "3.5" } });

    expect(onChangeSpy).toHaveBeenCalledWith(expect.objectContaining({ inputPricePer1M: 3.5 }));
  });

  it("clearing the field back to blank writes undefined, not 0 or NaN", () => {
    const onChangeSpy = vi.fn();
    renderWithProviders(
      <Harness
        initial={{ type: "openai", parameters: {}, outputPricePer1M: 10 } as LlmTask}
        onChangeSpy={onChangeSpy}
      />,
    );

    fireEvent.change(screen.getByTestId("task-output-price"), { target: { value: "" } });

    expect(onChangeSpy).toHaveBeenCalledWith(expect.objectContaining({ outputPricePer1M: undefined }));
  });

  it("marks both price inputs readOnly when the section is readOnly", () => {
    renderWithProviders(
      <TaskPricingSection task={{ type: "openai", parameters: {} } as LlmTask} onChange={vi.fn()} readOnly />,
    );
    expect(screen.getByTestId("task-input-price")).toHaveAttribute("readonly");
    expect(screen.getByTestId("task-output-price")).toHaveAttribute("readonly");
  });
});
