import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { type ReactNode } from "react";
import { useCurrentScreenContext } from "../use-current-screen-context";

function renderAt(path: string) {
  return renderHook(() => useCurrentScreenContext(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
    ),
  });
}

describe("useCurrentScreenContext", () => {
  it("resolves id-bearing routes with the right param name", () => {
    expect(renderAt("/manage/studio/agent-1").result.current).toEqual({
      screen: "agent-studio",
      agentId: "agent-1",
    });
    expect(renderAt("/manage/agentview/agent-2").result.current).toEqual({
      screen: "agent-detail",
      agentId: "agent-2",
    });
    expect(renderAt("/manage/workflowview/wf-1").result.current).toEqual({
      screen: "workflow-detail",
      workflowId: "wf-1",
    });
    expect(renderAt("/manage/groups/grp-1").result.current).toEqual({
      screen: "group-detail",
      groupId: "grp-1",
    });
    expect(renderAt("/manage/channels/ch-1").result.current).toEqual({
      screen: "channel-detail",
      channelId: "ch-1",
    });
    expect(renderAt("/manage/conversationview/conv-1").result.current).toEqual({
      screen: "conversation-detail",
      conversationId: "conv-1",
    });
  });

  it("resolves a two-param route", () => {
    expect(renderAt("/manage/resources/agents/res-1").result.current).toEqual({
      screen: "resource-detail",
      resourceType: "agents",
      resourceId: "res-1",
    });
  });

  it("checks a literal segment before the param pattern it collides with", () => {
    // Without the literal-first ordering, "wizard" would be captured as
    // groupId/agentId — a real id-looking value that just happens to be wrong.
    expect(renderAt("/manage/groups/wizard").result.current).toEqual({ screen: "group-wizard" });
    expect(renderAt("/manage/agents/wizard").result.current).toEqual({ screen: "agent-wizard" });
  });

  it("checks the deeper workforce path before its own prefix", () => {
    expect(renderAt("/workforce/board-1/thread/member-1").result.current).toEqual({
      screen: "workforce-thread",
      boardId: "board-1",
      memberId: "member-1",
    });
    expect(renderAt("/workforce/board-1/settings").result.current).toEqual({
      screen: "workforce-settings",
      boardId: "board-1",
    });
    expect(renderAt("/workforce/board-1/history").result.current).toEqual({
      screen: "workforce-history",
      boardId: "board-1",
    });
    expect(renderAt("/workforce/board-1").result.current).toEqual({
      screen: "workforce-board",
      boardId: "board-1",
    });
  });

  it("checks workforce's literal routes before the :boardId pattern they collide with", () => {
    expect(renderAt("/workforce/new").result.current).toEqual({ screen: "workforce-wizard" });
    expect(renderAt("/workforce/analytics").result.current).toEqual({ screen: "workforce-analytics" });
    expect(renderAt("/workforce/chat").result.current).toEqual({ screen: "workforce-chat" });
  });

  it("resolves param-free routes with no id fields set", () => {
    expect(renderAt("/manage").result.current).toEqual({ screen: "dashboard" });
    expect(renderAt("/manage/agents").result.current).toEqual({ screen: "agents" });
    expect(renderAt("/manage/operator").result.current).toEqual({ screen: "operator" });
    expect(renderAt("/workforce").result.current).toEqual({ screen: "workforce-dashboard" });
  });

  it("checks /manage/conversations/monitoring before the plain conversations route", () => {
    expect(renderAt("/manage/conversations/monitoring").result.current).toEqual({
      screen: "conversation-monitoring",
    });
    expect(renderAt("/manage/conversations").result.current).toEqual({ screen: "conversations" });
  });

  it("falls back to a generic screen for an unmatched path", () => {
    expect(renderAt("/welcome").result.current).toEqual({ screen: "other" });
    expect(renderAt("/manage/some-future-page").result.current).toEqual({ screen: "other" });
  });
});
