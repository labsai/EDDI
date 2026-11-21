import { describe, it, expect, vi } from "vitest";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { EditorValidityContext } from "@eddi/app-kit/components/editors/editor-validity";
import { TaskGuardrailsSection } from "../task-guardrails-section";
import type { LlmTask } from "@eddi/app-kit/components/editors/llm/types";

function task(patterns: string): LlmTask {
  return {
    type: "openai",
    parameters: {},
    inputGuardrails: [{ type: "regex-filter", action: "block", parameters: { patterns } }],
  } as LlmTask;
}

describe("TaskGuardrailsSection — pattern validity", () => {
  it("reports an invalid regex-filter pattern to the layout even though the sheet is closed", () => {
    const report = vi.fn();
    renderWithProviders(
      <EditorValidityContext.Provider value={{ report }}>
        <TaskGuardrailsSection task={task("(unclosed")} onChange={() => {}} />
      </EditorValidityContext.Provider>,
    );
    expect(report).toHaveBeenCalledWith(expect.any(String), expect.stringContaining("1"));
  });

  it("reports nothing for a valid pattern or one only Java can check", () => {
    for (const patterns of ["secret", "a*+"]) {
      const report = vi.fn();
      renderWithProviders(
        <EditorValidityContext.Provider value={{ report }}>
          <TaskGuardrailsSection task={task(patterns)} onChange={() => {}} />
        </EditorValidityContext.Provider>,
      );
      expect(report.mock.calls.every(([, reason]) => reason === null)).toBe(true);
    }
  });
});
