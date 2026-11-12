import { describe, expect, it } from "vitest";
import type { UserEvent } from "@eddi/core/user-events";
import type { PendingApprovalSummary } from "@eddi/app-kit/lib/api/hitl";
import en from "@/i18n/locales/en.json";
import { approvalItems, MANAGER_APPROVALS_HREF } from "@/lib/workforce-approvals";
import { isShowing, noticeFor } from "@/lib/workforce-notices";

const event = (fields: Partial<UserEvent> & Pick<UserEvent, "kind">): UserEvent => ({ id: "e1", ts: 1, ...fields });

describe("approvalItems (T2.3)", () => {
  const PAUSED = "2026-11-06T10:00:00Z";
  const regular: PendingApprovalSummary[] = [
    { conversationId: "c-mine", agentId: "a1", userId: "ann", pausedAt: PAUSED, timeoutPolicy: "WAIT_INDEFINITELY" },
    { conversationId: "c-other", agentId: "a2", userId: "bob", pausedAt: PAUSED, timeoutPolicy: "AUTO_REJECT", approvalTimeout: "PT15M" },
  ];
  const group: PendingApprovalSummary[] = [
    { conversationId: "gc-mine", groupId: "g1", userId: "ann", pausedAt: PAUSED, pauseType: "HUMAN_TURN", timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT1H" },
    // The same conversation in both lists counts once.
    { conversationId: "c-mine", agentId: "a1", userId: "ann", pausedAt: PAUSED },
  ];

  it("splits the person's own from those they review, by the owner the summary records", () => {
    const items = approvalItems(regular, group, "ann");
    expect(items.map((i) => [i.conversationId, i.mine])).toEqual([
      ["c-other", false],
      ["gc-mine", true],
      ["c-mine", true],
    ]);
  });

  it("orders by what decides itself soonest, and computes the deadline as pausedAt + approvalTimeout", () => {
    const [first, second, third] = approvalItems(regular, group, "ann");
    expect(first?.deadline).toBe(Date.parse(PAUSED) + 15 * 60_000);
    expect(second?.deadline).toBe(Date.parse(PAUSED) + 60 * 60_000);
    expect(third?.deadline).toBeNull();
  });

  it("opens one's own chat or team session in Workforce, and a review in the Manager's inbox", () => {
    const byId = Object.fromEntries(approvalItems(regular, group, "ann").map((i) => [i.conversationId, i]));
    expect(byId["c-mine"]?.href).toBe("/workforce/chat?agentId=a1&conversationId=c-mine");
    expect(byId["gc-mine"]?.href).toBe("/workforce/g1?conversation=gc-mine");
    expect(byId["gc-mine"]?.humanTurn).toBe(true);
    expect(byId["c-other"]?.href).toBe(MANAGER_APPROVALS_HREF);
  });

  it("with nobody signed in (auth off), everything is one's own", () => {
    expect(approvalItems(regular, group, null).every((i) => i.mine)).toBe(true);
  });
});

describe("noticeFor (T2.8)", () => {
  it("a finished job links to its chat; why it stopped picks the sentence", () => {
    expect(noticeFor(event({ kind: "job.finished", conversationId: "c1", agentId: "a1", state: "done" }), false)).toEqual({
      key: "jobFinished",
      tone: "info",
      href: "/workforce/chat?agentId=a1&conversationId=c1",
      conversationId: "c1",
    });
    expect(noticeFor(event({ kind: "job.finished", conversationId: "c1", state: "limit" }), false)?.key).toBe("jobStoppedLimit");
    expect(noticeFor(event({ kind: "job.finished", conversationId: "c1", state: "cost_ceiling" }), false)?.key).toBe("jobStoppedCost");
    expect(noticeFor(event({ kind: "job.finished", conversationId: "c1", state: "something_new" }), false)?.key).toBe("jobStopped");
  });

  it("a failed run is an error, and a schedule's says so", () => {
    expect(noticeFor(event({ kind: "job.failed", jobKind: "schedule", conversationId: "c1" }), false)).toMatchObject({ key: "scheduleFailed", tone: "error" });
    expect(noticeFor(event({ kind: "job.failed", jobKind: "continuation", conversationId: "c1" }), false)?.key).toBe("jobFailed");
    expect(noticeFor(event({ kind: "job.run_completed", jobKind: "schedule", conversationId: "c1" }), false)?.key).toBe("scheduleCompleted");
  });

  it("a team that ended links to its session", () => {
    expect(noticeFor(event({ kind: "team.ended", groupId: "g1", groupConversationId: "gc1", state: "COMPLETED" }), false)).toEqual({
      key: "teamEnded",
      tone: "info",
      href: "/workforce/g1?conversation=gc1",
      conversationId: "gc1",
    });
    expect(noticeFor(event({ kind: "team.ended", groupId: "g1", groupConversationId: "gc1", state: "FAILED" }), false)).toMatchObject({ key: "teamFailed", tone: "error" });
    expect(noticeFor(event({ kind: "team.ended", groupId: "g1", groupConversationId: "gc1", state: "CANCELLED" }), false)?.key).toBe("teamStopped");
  });

  it("a team waiting for a human is news only on the copy that says it is your turn", () => {
    const waiting = { kind: "team.waiting_for_human" as const, groupId: "g1", groupConversationId: "gc1" };
    expect(noticeFor(event({ ...waiting, yourTurn: true }), false)?.key).toBe("teamYourTurn");
    expect(noticeFor(event({ ...waiting, yourTurn: false }), false)).toBeNull();
    expect(noticeFor(event(waiting), false)).toBeNull();
  });

  it("a decision is news only when the pause was one's own", () => {
    const decided = event({ kind: "approval.decided", surface: "regular", conversationId: "c1", agentId: "a1", state: "APPROVED" });
    expect(noticeFor(decided, true)).toMatchObject({ key: "approvalApproved", href: "/workforce/chat?agentId=a1&conversationId=c1" });
    expect(noticeFor(decided, false)).toBeNull();
    expect(noticeFor({ ...decided, state: "REJECTED" }, true)?.key).toBe("approvalRejected");
    expect(noticeFor({ ...decided, state: "MIXED" }, true)?.key).toBe("approvalMixed");
    expect(noticeFor({ ...decided, state: "ABORTED" }, true)?.key).toBe("approvalStopped");
  });

  it("approval.pending, team.started and notification.created raise no toast", () => {
    expect(noticeFor(event({ kind: "approval.pending", conversationId: "c1" }), true)).toBeNull();
    expect(noticeFor(event({ kind: "team.started", groupId: "g1" }), false)).toBeNull();
    expect(noticeFor(event({ kind: "notification.created", notificationId: "n1" }), false)).toBeNull();
  });

  it("every sentence it can pick is in the locale files", () => {
    const notices = (en as { Workforce: { notices: Record<string, string> } }).Workforce.notices;
    const keys = [
      "jobFinished", "jobStoppedLimit", "jobStoppedCost", "jobStopped", "scheduleCompleted", "scheduleFailed", "jobFailed",
      "teamEnded", "teamFailed", "teamStopped", "teamYourTurn",
      "approvalApproved", "approvalRejected", "approvalMixed", "approvalStopped", "approvalDecided",
    ];
    for (const key of keys) expect(notices[key], key).toBeTruthy();
  });
});

describe("isShowing", () => {
  it("matches the chat or team session in the address bar", () => {
    expect(isShowing({ pathname: "/workforce/chat", search: "?agentId=a1&conversationId=c1" }, "c1")).toBe(true);
    expect(isShowing({ pathname: "/workforce/g1", search: "?conversation=gc1" }, "gc1")).toBe(true);
    expect(isShowing({ pathname: "/workforce/chat", search: "?agentId=a1&conversationId=c2" }, "c1")).toBe(false);
    expect(isShowing({ pathname: "/workforce", search: "" }, null)).toBe(false);
  });
});
