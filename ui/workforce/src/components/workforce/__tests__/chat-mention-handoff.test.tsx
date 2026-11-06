import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";
import {
  filterMentionCandidates,
  markerPositions,
  mentionQueryAt,
  type ChatMentionCandidate,
} from "@eddi/app-kit/lib/chat-mention";
import {
  HANDOFF_QUESTION_CHARS,
  addHandoff,
  clearWorkforceHandoffs,
  completeHandoff,
  handoffsStorageKey,
  readHandoffs,
  type WorkforceHandoff,
} from "@eddi/app-kit/lib/workforce-handoffs";
import type { CatalogAgent, CatalogGroup } from "@eddi/app-kit/lib/api/catalog";
import { mentionCandidates } from "@/lib/mention-candidates";
import { handoffTargetHref, handoffOriginHref } from "@/lib/handoff-links";
import { HandoffOriginMarker, HandoffTargetMarker } from "@/components/workforce/handoff-marker";
import { readInitialTeamQuestion, workforceTeamStart } from "@/lib/workforce-team-start";

/**
 * T2.2 — @mention an agent or a team in a chat to hand the question over.
 * The composer is a combobox over the entries the host allows; a hand-off
 * never sends to the chat's own agent; the transcript gets a marker that
 * links to where the question went. The hand-off is recorded in this browser
 * only (no backend record exists), which the marker's title says.
 */

const CANDIDATES: ChatMentionCandidate[] = [
  { kind: "agent", id: "a-ada", name: "Ada from Support" },
  { kind: "agent", id: "a-travel", name: "Travel Planner" },
  { kind: "team", id: "grp1", name: "Product Review Panel" },
];

describe("mentionQueryAt", () => {
  it("finds the @query at the caret, at the start or after a space", () => {
    expect(mentionQueryAt("@ad", 3)).toEqual({ start: 0, query: "ad" });
    expect(mentionQueryAt("ask @tra now", 8)).toEqual({ start: 4, query: "tra" });
    expect(mentionQueryAt("@", 1)).toEqual({ start: 0, query: "" });
  });

  it("is not fooled by an e-mail address or a finished word", () => {
    expect(mentionQueryAt("mail ada@example.com", 20)).toBeNull();
    expect(mentionQueryAt("@ada done", 9)).toBeNull();
    expect(mentionQueryAt("no mention", 10)).toBeNull();
  });
});

describe("filterMentionCandidates", () => {
  it("puts names starting with the query first, then those containing it", () => {
    expect(filterMentionCandidates(CANDIDATES, "p").map((c) => c.id)).toEqual(["grp1", "a-ada", "a-travel"]);
    expect(filterMentionCandidates(CANDIDATES, "PLAN").map((c) => c.id)).toEqual(["a-travel"]);
    expect(filterMentionCandidates(CANDIDATES, "zzz")).toEqual([]);
    expect(filterMentionCandidates(CANDIDATES, "", 2)).toHaveLength(2);
  });
});

describe("mentionCandidates — only what the catalog lists", () => {
  const agents = [
    { agentId: "a-ada", name: "Ada", appearance: { displayName: "Ada from Support" }, callerLevel: "USE" },
    { agentId: "a-self", name: "This chat's agent", callerLevel: "USE" },
  ] as unknown as CatalogAgent[];
  const groups = [{ groupId: "grp1", version: 2, name: "Product Review Panel", members: [], callerLevel: "USE" }] as unknown as CatalogGroup[];

  it("offers the catalog's agents (not this chat's own) and teams", () => {
    expect(mentionCandidates(agents, groups, "a-self")).toEqual([
      { kind: "agent", id: "a-ada", name: "Ada from Support", description: undefined },
      { kind: "team", id: "grp1", name: "Product Review Panel", description: undefined },
    ]);
  });

  it("offers nothing before the catalog has answered", () => {
    expect(mentionCandidates(undefined, undefined, null)).toEqual([]);
  });
});

describe("hand-off records (this browser only)", () => {
  afterEach(() => clearWorkforceHandoffs());

  const base = {
    from: { agentId: "agent1", agentName: "Support Bot", conversationId: "conv1", afterStep: 2 },
    to: { kind: "agent" as const, id: "a-ada", name: "Ada", conversationId: null },
    question: "Where is my parcel?",
  };

  it("are kept per principal, cut to a short excerpt, and completed with the target conversation", () => {
    const h = addHandoff("alice", { ...base, question: "x".repeat(HANDOFF_QUESTION_CHARS + 50) });
    expect(h.question).toHaveLength(HANDOFF_QUESTION_CHARS);
    expect(readHandoffs("bob")).toEqual([]);
    completeHandoff("alice", h.id, "conv-new");
    expect(readHandoffs("alice")[0]!.to.conversationId).toBe("conv-new");
    expect(localStorage.getItem(handoffsStorageKey("alice"))).toContain("conv-new");
  });

  it("are all removed on sign-out", () => {
    addHandoff("alice", base);
    addHandoff("bob", base);
    clearWorkforceHandoffs();
    expect(readHandoffs("alice")).toEqual([]);
    expect(localStorage.getItem(handoffsStorageKey("bob"))).toBeNull();
  });

  it("ignore malformed storage", () => {
    localStorage.setItem(handoffsStorageKey("eve"), '[{"id":1},"x",null]');
    expect(readHandoffs("eve")).toEqual([]);
  });
});

describe("hand-off links and the team start state", () => {
  const handoff: WorkforceHandoff = {
    id: "h1",
    createdAt: "2026-11-06T10:00:00Z",
    from: { agentId: "agent1", agentName: "Support Bot", conversationId: "conv1", afterStep: 1 },
    to: { kind: "team", id: "grp1", name: "Product Review Panel", version: 2, conversationId: "gc9" },
    question: "Should we ship?",
  };

  it("link to the session a team hand-off started, and back to the chat", () => {
    expect(handoffTargetHref(handoff)).toBe("/workforce/grp1?version=2&conversation=gc9");
    expect(handoffTargetHref({ ...handoff, to: { ...handoff.to, kind: "agent", id: "a-ada" } })).toBe(
      "/workforce/chat?agentId=a-ada&conversationId=gc9",
    );
    expect(handoffTargetHref({ ...handoff, to: { ...handoff.to, conversationId: null } })).toBeNull();
    expect(handoffOriginHref(handoff)).toBe("/workforce/chat?agentId=agent1&conversationId=conv1");
  });

  it("carry the question in router state, never in the URL", () => {
    const { to, state } = workforceTeamStart("grp1", "Should we ship?", { version: 2, handoffId: "h1" });
    expect(to).toBe("/workforce/grp1?version=2");
    expect(to).not.toContain("ship");
    expect(readInitialTeamQuestion(state)).toEqual({ groupId: "grp1", text: "Should we ship?", handoffId: "h1" });
    expect(readInitialTeamQuestion({ initialTeamQuestion: { groupId: "grp1", text: "  " } })).toBeNull();
    expect(readInitialTeamQuestion(null)).toBeNull();
  });

  it("render as markers that say where the question went and came from", () => {
    renderWithProviders(
      <>
        <HandoffOriginMarker handoff={handoff} />
        <HandoffTargetMarker handoff={handoff} />
        <HandoffOriginMarker handoff={{ ...handoff, id: "h2", to: { ...handoff.to, kind: "agent", name: "Ada", conversationId: null } }} />
      </>,
    );
    const origin = screen.getByTestId("handoff-origin-h1");
    expect(origin).toHaveTextContent("You handed “Should we ship?” over to the team Product Review Panel.");
    expect(origin).toHaveAttribute("title", "Recorded in this browser only");
    expect(screen.getByTestId("handoff-open-h1")).toHaveAttribute("href", "/workforce/grp1?version=2&conversation=gc9");
    expect(screen.getByTestId("handoff-target-h1")).toHaveTextContent("Handed over from your chat with Support Bot.");
    expect(screen.getByTestId("handoff-back-h1")).toHaveAttribute("href", "/workforce/chat?agentId=agent1&conversationId=conv1");
    expect(screen.getByTestId("handoff-origin-h2")).toHaveTextContent("It did not start.");
    expect(screen.queryByTestId("handoff-open-h2")).toBeNull();
  });
});

describe("markerPositions", () => {
  const messages = [
    { role: "agent" as const, content: "Hi" },
    { role: "user" as const, content: "Q1" },
    { role: "agent" as const, content: "A1" },
    { role: "agent" as const, content: "A1b" },
    { role: "user" as const, content: "Q2" },
    { role: "agent" as const, content: "A2" },
  ];
  it("puts a marker after its step's last message, at the top for none, at the end for an undone step", () => {
    const { top, after, end } = markerPositions(messages, [
      { id: "m1", afterStep: 1, content: null },
      { id: "m0", afterStep: null, content: null },
      { id: "m9", afterStep: 9, content: null },
    ]);
    expect(top.map((m) => m.id)).toEqual(["m0"]);
    expect([...after.entries()].map(([i, ms]) => [i, ms.map((m) => m.id)])).toEqual([[3, ["m1"]]]);
    expect(end.map((m) => m.id)).toEqual(["m9"]);
  });
});

describe("ChatPanel — @mention combobox", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().replaceMessages([
      { id: "a0", role: "agent", content: "Welcome", timestamp: 1 },
      { id: "u1", role: "user", content: "Hello", timestamp: 2 },
      { id: "a1", role: "agent", content: "Hi there", timestamp: 3 },
    ]);
    server.use(
      http.get("*/agents/:agentId/profile", ({ params }) =>
        HttpResponse.json({ agentId: String(params.agentId), reviewNotice: null }),
      ),
    );
  });

  function setup(onHandOff = vi.fn(() => true)) {
    const sent: unknown[] = [];
    server.use(
      http.post("*/agents/:convId/stream", async ({ request }) => {
        sent.push(await request.text());
        return new HttpResponse("", { status: 500 });
      }),
      http.post("*/agents/:convId", async ({ request }) => {
        sent.push(await request.text());
        return new HttpResponse("", { status: 500 });
      }),
    );
    renderWithProviders(<ChatPanel mention={{ candidates: CANDIDATES, onHandOff }} />);
    const input = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    const type = (value: string) => {
      fireEvent.change(input, { target: { value, selectionStart: value.length, selectionEnd: value.length } });
    };
    return { input, type, onHandOff, sent };
  }

  it("is a combobox that opens on @ with the allowed entries, filtered as you type", async () => {
    const { input, type } = setup();
    await waitFor(() => expect(input).not.toBeDisabled());
    expect(input).toHaveAttribute("role", "combobox");
    expect(input).toHaveAttribute("aria-expanded", "false");

    type("@");
    const listbox = screen.getByRole("listbox", { name: "Hand over to" });
    expect(input).toHaveAttribute("aria-expanded", "true");
    expect(input).toHaveAttribute("aria-controls", listbox.id);
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Ada from SupportAgent",
      "Travel PlannerAgent",
      "Product Review PanelTeam",
    ]);
    expect(screen.getByTestId("chat-mention-status")).toHaveTextContent("Suggestions: 3.");

    type("@pro");
    expect(screen.getAllByRole("option")).toHaveLength(1);
    expect(screen.getByRole("option")).toHaveTextContent("Product Review Panel");
  });

  it("moves with the arrow keys and picks with Enter; the hand-off replaces the send", async () => {
    const { input, type, onHandOff, sent } = setup();
    await waitFor(() => expect(input).not.toBeDisabled());
    type("@");
    const options = () => screen.getAllByRole("option");
    expect(options()[0]).toHaveAttribute("aria-selected", "true");
    expect(input).toHaveAttribute("aria-activedescendant", options()[0]!.id);

    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(options()[2]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(options()[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(input).toHaveAttribute("aria-activedescendant", options()[2]!.id);

    fireEvent.keyDown(input, { key: "Enter" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(screen.getByTestId("chat-mention-target")).toHaveTextContent("Hand over to the team Product Review Panel");
    expect(input.value).toBe("");
    expect(input).toHaveAttribute("aria-describedby");
    expect(screen.getByTestId("chat-mention-status")).toHaveTextContent("Hand over to the team Product Review Panel");

    type("Should we ship the beta?");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onHandOff).toHaveBeenCalledTimes(1);
    expect(onHandOff).toHaveBeenCalledWith(CANDIDATES[2], "Should we ship the beta?");
    expect(input.value).toBe("");
    expect(screen.queryByTestId("chat-mention-target")).toBeNull();
    // Nothing went to this chat's agent.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(sent).toEqual([]);
  });

  it("Escape closes the list and keeps the text; Backspace drops the chosen target", async () => {
    const { input, type, onHandOff } = setup();
    await waitFor(() => expect(input).not.toBeDisabled());
    type("@tr");
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(input.value).toBe("@tr");
    // Still the dismissed @: stays closed.
    type("@tra");
    expect(screen.queryByRole("listbox")).toBeNull();

    // A new @ (the old one deleted) opens it again.
    type("");
    type("@ada");
    expect(screen.getByRole("listbox")).toBeInTheDocument();
    fireEvent.keyDown(input, { key: "Tab" });
    expect(screen.getByTestId("chat-mention-target")).toHaveTextContent("Hand over to Ada from Support");
    fireEvent.keyDown(input, { key: "Backspace" });
    expect(screen.queryByTestId("chat-mention-target")).toBeNull();
    expect(onHandOff).not.toHaveBeenCalled();
  });

  it("keeps the text when the host refuses the hand-off", async () => {
    const { input, type } = setup(vi.fn(() => false));
    await waitFor(() => expect(input).not.toBeDisabled());
    type("@ada");
    fireEvent.keyDown(input, { key: "Enter" });
    type("Where is my order?");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(input.value).toBe("Where is my order?");
    expect(screen.getByTestId("chat-mention-target")).toBeInTheDocument();
  });

  it("without a mention prop the composer is a plain textbox", async () => {
    renderWithProviders(<ChatPanel />);
    const input = screen.getByTestId("chat-input");
    expect(input).not.toHaveAttribute("role");
    fireEvent.change(input, { target: { value: "@a" } });
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("draws the host's transcript markers after their turn", async () => {
    renderWithProviders(
      <ChatPanel
        transcriptMarkers={[
          { id: "m1", afterStep: 1, content: <span>handed over</span> },
          { id: "m0", afterStep: null, content: <span>started from a hand-off</span> },
        ]}
      />,
    );
    const markers = await screen.findAllByTestId("chat-transcript-marker");
    expect(markers.map((m) => m.textContent)).toEqual(["started from a hand-off", "handed over"]);
    const reply = screen.getByText("Hi there");
    expect(reply.compareDocumentPosition(markers[1]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(markers[0]!.compareDocumentPosition(screen.getByText("Welcome")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
