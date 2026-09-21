import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TaskResponseValidationSection } from "@/components/editors/llm/task-response-validation-section";
import type { LlmTask } from "@/components/editors/llm/types";

const emptyTask: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
};

const enabledTask: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
  responseValidation: { enabled: true },
};

const taskWithTimeout: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
  streamingTimeoutSeconds: 45,
};

describe("TaskResponseValidationSection", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the section header", () => {
    renderWithProviders(
      <TaskResponseValidationSection task={emptyTask} onChange={onChange} />
    );
    expect(
      screen.getByText("Response Validation & Recovery")
    ).toBeInTheDocument();
  });

  it("renders a selector for each policy field when expanded", () => {
    // enabled=true auto-opens the section
    renderWithProviders(
      <TaskResponseValidationSection task={enabledTask} onChange={onChange} />
    );
    expect(screen.getByTestId("response-validation-section")).toBeInTheDocument();
    expect(screen.getByTestId("rv-onEmpty")).toBeInTheDocument();
    expect(screen.getByTestId("rv-onTruncation")).toBeInTheDocument();
    expect(screen.getByTestId("rv-onContentFilter")).toBeInTheDocument();
    expect(screen.getByTestId("rv-onRefusal")).toBeInTheDocument();
    expect(screen.getByTestId("rv-onStreamingTimeout")).toBeInTheDocument();
    expect(screen.getByTestId("rv-streaming-timeout-seconds")).toBeInTheDocument();
  });

  it("reflects backend defaults in the policy selects", () => {
    renderWithProviders(
      <TaskResponseValidationSection task={enabledTask} onChange={onChange} />
    );
    // Backend defaults: onRefusal = "ignore", all others = "warn"
    expect(screen.getByTestId("rv-onEmpty")).toHaveValue("warn");
    expect(screen.getByTestId("rv-onTruncation")).toHaveValue("warn");
    expect(screen.getByTestId("rv-onContentFilter")).toHaveValue("warn");
    expect(screen.getByTestId("rv-onRefusal")).toHaveValue("ignore");
    expect(screen.getByTestId("rv-onStreamingTimeout")).toHaveValue("warn");
  });

  it("calls onChange with the backend field name + value when a policy changes", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskResponseValidationSection task={enabledTask} onChange={onChange} />
    );
    await user.selectOptions(screen.getByTestId("rv-onTruncation"), "fallback");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        responseValidation: expect.objectContaining({
          enabled: true,
          onTruncation: "fallback",
        }),
      })
    );
  });

  it("calls onChange with onContentFilter = error", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskResponseValidationSection task={enabledTask} onChange={onChange} />
    );
    await user.selectOptions(screen.getByTestId("rv-onContentFilter"), "error");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        responseValidation: expect.objectContaining({
          onContentFilter: "error",
        }),
      })
    );
  });

  it("toggles the enabled master switch", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskResponseValidationSection task={emptyTask} onChange={onChange} />
    );
    // Section is collapsed by default (disabled + no timeout) — expand it first
    await user.click(screen.getByText("Response Validation & Recovery"));
    await user.click(screen.getByTestId("rv-enabled"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        responseValidation: expect.objectContaining({ enabled: true }),
      })
    );
  });

  it("round-trips streamingTimeoutSeconds", () => {
    const { rerender } = renderWithProviders(
      <TaskResponseValidationSection task={taskWithTimeout} onChange={onChange} />
    );
    // Existing value is shown (section auto-opens because timeout is set)
    expect(screen.getByTestId("rv-streaming-timeout-seconds")).toHaveValue(45);

    // Changing it emits the numeric value back through onChange
    fireEvent.change(screen.getByTestId("rv-streaming-timeout-seconds"), {
      target: { value: "90" },
    });
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ streamingTimeoutSeconds: 90 })
    );

    // And a committed value renders back into the control
    rerender(
      <TaskResponseValidationSection
        task={{ ...taskWithTimeout, streamingTimeoutSeconds: 90 }}
        onChange={onChange}
      />
    );
    expect(screen.getByTestId("rv-streaming-timeout-seconds")).toHaveValue(90);
  });

  it("clears streamingTimeoutSeconds to undefined when emptied", () => {
    renderWithProviders(
      <TaskResponseValidationSection task={taskWithTimeout} onChange={onChange} />
    );
    fireEvent.change(screen.getByTestId("rv-streaming-timeout-seconds"), {
      target: { value: "" },
    });
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ streamingTimeoutSeconds: undefined })
    );
  });

  it("disables controls when readOnly", () => {
    renderWithProviders(
      <TaskResponseValidationSection task={enabledTask} onChange={onChange} readOnly />
    );
    expect(screen.getByTestId("rv-enabled")).toBeDisabled();
    expect(screen.getByTestId("rv-onTruncation")).toBeDisabled();
    expect(screen.getByTestId("rv-streaming-timeout-seconds")).toHaveAttribute("readonly");
  });
});
