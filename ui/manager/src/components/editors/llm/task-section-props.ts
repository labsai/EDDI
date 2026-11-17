/**
 * Shared props interface for all TaskEditor sub-components.
 * Each section receives the task, an onChange callback, and a readOnly flag.
 */
import type { LlmTask } from "@eddi/app-kit/components/editors/llm/types";

export interface TaskSectionProps {
  task: LlmTask;
  onChange: (t: LlmTask) => void;
  readOnly?: boolean;
}
