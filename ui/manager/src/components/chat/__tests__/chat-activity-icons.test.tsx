import { describe, it, expect } from "vitest";
import { renderWithProviders } from "@/test/test-utils";
import { ChatActivity } from "@/components/chat/chat-activity";
import type { PipelineEvent } from "@/hooks/use-debug-events";

// Regression: SSE task events carry a task's getType() — "langchain",
// "behavior_rules", "httpCalls" — and the row looked that up as if it were an
// eddi:// extension id. It never matched, so every step rendered with the
// unknown-type icon in grey. These are the literal values the backend sends.
describe("ChatActivity — step icons", () => {
  const T = 1_700_000_000_000;
  const step = (taskType: string, index: number): PipelineEvent[] => [
    { type: "task_start", taskId: `t${index}`, taskType, index, timestamp: T + index * 10 },
    { type: "task_complete", taskId: `t${index}`, taskType, index, durationMs: 5, timestamp: T + index * 10 + 5 },
  ];

  it("draws each step with its own type's icon, not the unknown-type fallback", () => {
    const events = [...step("behavior_rules", 0), ...step("httpCalls", 1), ...step("langchain", 2)];
    const { container } = renderWithProviders(
      <ChatActivity events={events} isLive={false} showInternalSteps />,
    );

    expect(container.querySelector("svg.lucide-git-branch")).not.toBeNull(); // rules
    expect(container.querySelector("svg.lucide-globe")).not.toBeNull(); // API calls
    expect(container.querySelector("svg.lucide-brain")).not.toBeNull(); // LLM
    expect(container.querySelector("svg.lucide-package")).toBeNull(); // unknown
  });
});
