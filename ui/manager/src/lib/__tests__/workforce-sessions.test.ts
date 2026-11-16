import { describe, it, expect } from "vitest";
import {
  chatHref,
  chatSession,
  dateBucket,
  groupSessions,
  memberConversationIds,
  sessionLabel,
  teamSession,
  type WorkforceSession,
} from "@/lib/workforce-sessions";
import { readInitialChatMessage, workforceChatStart } from "@/lib/workforce-chat-start";
import type { ConversationDescriptor } from "@/lib/api/conversations";
import type { GroupConversation } from "@/lib/api/groups";

// Wednesday 2026-11-04, 10:00 local time.
const NOW = new Date(2026, 10, 4, 10, 0, 0).getTime();
const at = (y: number, m: number, d: number, h = 12) => new Date(y, m, d, h, 0, 0).getTime();

function session(id: string, lastActivity: number, extra: Partial<WorkforceSession> = {}): WorkforceSession {
  return {
    kind: "chat",
    id,
    title: `Title ${id}`,
    ownerId: "agent-1",
    ownerName: "Support",
    lastActivity,
    pinned: false,
    href: chatHref("agent-1", id),
    ...extra,
  };
}

describe("dateBucket", () => {
  it("buckets by local calendar day, not by 24-hour distance", () => {
    expect(dateBucket(at(2026, 10, 4, 0), NOW)).toBe("today");
    // 23:50 yesterday is "Yesterday" though only ~10 hours ago.
    expect(dateBucket(new Date(2026, 10, 3, 23, 50).getTime(), NOW)).toBe("yesterday");
    expect(dateBucket(at(2026, 10, 3, 0), NOW)).toBe("yesterday");
    expect(dateBucket(at(2026, 10, 2), NOW)).toBe("previous7");
    expect(dateBucket(at(2026, 9, 28), NOW)).toBe("previous7");
    expect(dateBucket(at(2026, 9, 27), NOW)).toBe("previous30");
    expect(dateBucket(at(2026, 9, 5), NOW)).toBe("previous30");
    expect(dateBucket(at(2026, 9, 4), NOW)).toBe("older");
  });
});

describe("groupSessions", () => {
  it("puts pinned first, then the date buckets newest first, then one group per older month", () => {
    const groups = groupSessions(
      [
        session("old-aug", at(2026, 7, 10)),
        session("today-1", at(2026, 10, 4, 8)),
        session("pinned-old", at(2026, 5, 1), { pinned: true }),
        session("yesterday", at(2026, 10, 3)),
        session("today-2", at(2026, 10, 4, 9)),
        session("week", at(2026, 9, 30)),
        session("month", at(2026, 9, 15)),
        session("old-sep", at(2026, 8, 20)),
        session("old-sep-2", at(2026, 8, 2)),
      ],
      NOW,
    );
    expect(groups.map((g) => g.key)).toEqual([
      "pinned",
      "today",
      "yesterday",
      "previous7",
      "previous30",
      "month:2026-09",
      "month:2026-08",
    ]);
    expect(groups[0]!.sessions.map((s) => s.id)).toEqual(["pinned-old"]);
    expect(groups[1]!.sessions.map((s) => s.id)).toEqual(["today-2", "today-1"]);
    expect(groups[5]!.sessions.map((s) => s.id)).toEqual(["old-sep", "old-sep-2"]);
    expect(groups[5]!.month).toEqual(new Date(2026, 8, 1));
  });

  it("leaves a pinned session out of its date group and empty groups out entirely", () => {
    const groups = groupSessions([session("a", at(2026, 10, 4), { pinned: true })], NOW);
    expect(groups.map((g) => g.key)).toEqual(["pinned"]);
    expect(groupSessions([], NOW)).toEqual([]);
  });
});

describe("sessionLabel", () => {
  const fmt = () => "Nov 4";
  it("is the title when there is one", () => {
    expect(sessionLabel({ title: "  Order status ", ownerName: "Support", ownerId: "a1", lastActivity: 0 }, fmt)).toBe(
      "Order status",
    );
  });
  it("falls back to the agent's name and the date", () => {
    expect(sessionLabel({ title: null, ownerName: "Support", ownerId: "a1", lastActivity: 0 }, fmt)).toBe("Support · Nov 4");
    expect(sessionLabel({ title: "   ", ownerName: "", ownerId: "a1", lastActivity: 0 }, fmt)).toBe("a1 · Nov 4");
  });
});

describe("chatSession", () => {
  const descriptor = (extra: Partial<ConversationDescriptor> = {}): ConversationDescriptor => ({
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv-1",
    name: "",
    description: "",
    createdOn: 1,
    lastModifiedOn: 2,
    agentId: "agent-1",
    agentVersion: 1,
    conversationState: "READY",
    agentName: "Stored name",
    title: "Hello",
    pinned: true,
    ...extra,
  });

  it("opens the conversation by both ids, in the URL", () => {
    const s = chatSession(descriptor(), new Map([["agent-1", "Support"]]));
    expect(s).toMatchObject({
      kind: "chat",
      id: "conv-1",
      title: "Hello",
      ownerName: "Support",
      lastActivity: 2,
      pinned: true,
      href: "/workforce/chat?agentId=agent-1&conversationId=conv-1",
    });
  });

  it("names the agent from the descriptor when the agent list does not know it", () => {
    expect(chatSession(descriptor(), new Map())?.ownerName).toBe("Stored name");
  });

  it("skips a descriptor without an agent", () => {
    expect(chatSession(descriptor({ agentId: "" }), new Map())).toBeNull();
  });
});

describe("team sessions", () => {
  const gc = {
    id: "gc-1",
    groupId: "grp-1",
    userId: "u",
    state: "COMPLETED",
    originalQuestion: "Should we ship?",
    transcript: [],
    memberConversationIds: { a1: "conv-m1", a2: "conv-m2" },
    lastModified: "2026-11-04T08:00:00Z",
  } as unknown as GroupConversation;

  it("is titled by its question and opens on the board", () => {
    expect(teamSession(gc, "Review panel")).toMatchObject({
      kind: "team",
      title: "Should we ship?",
      ownerName: "Review panel",
      lastActivity: Date.parse("2026-11-04T08:00:00Z"),
      pinned: false,
      href: "/workforce/grp-1?conversation=gc-1",
    });
  });

  it("collects its members' own conversations so they are not listed as chats", () => {
    expect([...memberConversationIds([gc])].sort()).toEqual(["conv-m1", "conv-m2"]);
  });
});

describe("workforceChatStart", () => {
  it("puts only the agent in the URL and the text in router state", () => {
    const { to, state } = workforceChatStart("agent 1", "Where is my order?");
    expect(to).toBe("/workforce/chat?agentId=agent%201");
    expect(to).not.toContain("order");
    expect(readInitialChatMessage(state)).toEqual({ agentId: "agent 1", text: "Where is my order?" });
  });

  it("reads nothing from state it did not write", () => {
    expect(readInitialChatMessage(null)).toBeNull();
    expect(readInitialChatMessage({ initialMessage: { agentId: "a", text: "  " } })).toBeNull();
    expect(readInitialChatMessage({ initialMessage: { agentId: 1, text: "x" } })).toBeNull();
    expect(readInitialChatMessage("x")).toBeNull();
  });
});
