import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import type {
  ConversationDescriptor,
  SimpleConversationMemorySnapshot,
} from "@/lib/api/conversations";

/**
 * Restoring an operator conversation across a navigation, a reload, and a
 * browser restart.
 *
 * The bug: the operator chat lived only in a module-level store, so navigating
 * away and back lost the transcript, and a browser restart lost the id too —
 * an investigation became unreachable while still running on the server.
 */
const h = vi.hoisted(() => ({
  /** Snapshots returned by getSimpleConversationLog, in call order. */
  logs: [] as Array<Partial<SimpleConversationMemorySnapshot>>,
  /** Thrown instead of the next snapshot, when set. */
  logError: null as unknown,
  descriptors: [] as ConversationDescriptor[],
  descriptorPages: null as ConversationDescriptor[][] | null,
  descriptorCalls: [] as Array<{ limit: number; agentId: string }>,
  logCalls: [] as Array<{ conversationId: string; returnCurrentStepOnly: boolean }>,
  /** Runs inside the snapshot read, before it resolves. */
  duringLogRead: null as null | (() => void),
}));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    startConversation: vi.fn(async () => "conv-new"),
    sendMessageStreaming: async function* () {
      yield { type: "done", data: JSON.stringify({ conversationState: "READY" }) };
    },
  };
});

vi.mock("@/lib/api/conversations", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/conversations")>();
  return {
    ...actual,
    getSimpleConversationLog: vi.fn(
      async (conversationId: string, _detailed: boolean, returnCurrentStepOnly: boolean) => {
        h.logCalls.push({ conversationId, returnCurrentStepOnly });
        h.duringLogRead?.();
        if (h.logError) throw h.logError;
        const next = h.logs.shift();
        if (!next) throw new Error("test bug: ran out of mocked conversation logs");
        return next as SimpleConversationMemorySnapshot;
      },
    ),
    getConversationDescriptors: vi.fn(
      async (limit: number, index: number, _filter: string, agentId: string) => {
        h.descriptorCalls.push({ limit, agentId });
        // `descriptorPages` drives the paging tests; `descriptors` stays the
        // single-page shorthand every other test uses.
        if (h.descriptorPages) return h.descriptorPages[index] ?? [];
        return index === 0 ? h.descriptors : [];
      },
    ),
  };
});

import { useOperatorChat, useOperatorChatStore } from "../use-operator-chat";
import { OPERATOR_PROBE_USER_ID, type OperatorConfig } from "@/lib/api/operator";

function config(): OperatorConfig {
  return {
    enabled: true,
    agentId: "operator-agent",
    version: 1,
    environment: "production",
    provider: "anthropic",
    model: "claude-sonnet-5",
    credentialKey: null,
    scope: "read_write",
    authMode: "caller-identity",
    promptBody: "Do the thing.",
  };
}

/** A stored conversation step carrying the user's message. */
function step(input: string) {
  return { conversationStep: [{ key: "input:initial", value: input }] };
}

function textOutput(text: string) {
  return { output: [{ type: "text", text }] };
}

function descriptor(
  id: string,
  overrides: Partial<ConversationDescriptor> = {},
): ConversationDescriptor {
  return {
    resource: `eddi://ai.labs.conversation/conversationstore/conversations/${id}`,
    name: "",
    description: "",
    createdOn: 1000,
    lastModifiedOn: 1000,
    agentId: "operator-agent",
    agentVersion: 1,
    conversationState: "READY",
    ...overrides,
  };
}

/** A two-turn transcript. */
const TWO_TURNS: Partial<SimpleConversationMemorySnapshot> = {
  conversationState: "READY",
  conversationSteps: [step("what is deployed?"), step("and the logs?")],
  conversationOutputs: [textOutput("Three agents."), textOutput("All clean.")],
};

beforeEach(() => {
  h.logs = [];
  h.logError = null;
  h.descriptors = [];
  h.descriptorPages = null;
  h.descriptorCalls = [];
  h.logCalls = [];
  h.duringLogRead = null;
  // reset() AFTER the store reset, not before: reset() deliberately writes the
  // "the admin cleared this chat, do not restore it" tombstone, so clearing
  // storage first would leave every test starting as if the user had just
  // pressed New conversation — and recovery would correctly decline.
  useOperatorChatStore.getState().reset();
  sessionStorage.clear();
});

/** Put a conversation id in storage the way a previous visit would have. */
function storeId(id: string) {
  sessionStorage.setItem("eddi.operator.conversationId", id);
  useOperatorChatStore.setState({ conversationId: id });
}

describe("hydrate: restoring the stored conversation", () => {
  it("rebuilds user and agent messages in transcript order", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.messages.map((m) => [m.role, m.content])).toEqual([
      ["user", "what is deployed?"],
      ["agent", "Three agents."],
      ["user", "and the logs?"],
      ["agent", "All clean."],
    ]);
    expect(result.current.conversationId).toBe("conv-1");
  });

  /**
   * In `returnCurrentStepOnly` mode the backend collapses conversationOutputs
   * to a single element, so every step would pair against the last turn's
   * answer. Reading the whole transcript is the entire point.
   */
  it("reads the WHOLE transcript, not just the current step", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(h.logCalls).toEqual([{ conversationId: "conv-1", returnCurrentStepOnly: false }]);
  });

  it("restores a paused conversation so the approval card comes back", async () => {
    storeId("conv-1");
    h.logs = [
      {
        conversationState: "AWAITING_HUMAN",
        hitlPausedAt: "2026-08-15T10:00:00Z",
        conversationSteps: [step("build me an agent")],
        conversationOutputs: [textOutput("I need your approval to run setupAgent.")],
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.isPaused).toBe(true);
    // Null from the snapshot ON PURPOSE: the simple snapshot never carries a
    // reason on the wire; the rendered reason overlays from approval-status.
    expect(result.current.pauseReason).toBeNull();
    // The ask bubble is what resolveApproval inserts the decision AFTER, so it
    // has to be the agent message, never the user's request above it.
    const ask = result.current.messages.find((m) => m.role === "agent");
    expect(result.current.pausedPlaceholderId).toBe(ask?.id);
  });

  it("leaves a READY conversation unpaused", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.isPaused).toBe(false);
    expect(result.current.pausedPlaceholderId).toBeNull();
  });

  /**
   * A conversation can be purged (`purgeEndedConversations`) or deleted with
   * the operator it belonged to. A stored id pointing at nothing must leave a
   * working empty chat, not a banner about a conversation nobody remembers.
   */
  it("clears the stored id on 404 and leaves an empty, usable chat", async () => {
    storeId("conv-gone");
    h.logError = Object.assign(new Error("Not Found"), { status: 404 });

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.messages).toEqual([]);
    expect(result.current.conversationId).toBeNull();
    expect(result.current.error).toBeNull();
    expect(sessionStorage.getItem("eddi.operator.conversationId")).toBeNull();
  });

  it("surfaces a non-404 failure rather than pretending the chat is empty", async () => {
    storeId("conv-1");
    h.logError = Object.assign(new Error("Service Unavailable"), { status: 503 });

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.error).toBeTruthy();
    // The id survives: the conversation is probably fine, the backend is not.
    expect(sessionStorage.getItem("eddi.operator.conversationId")).toBe("conv-1");
  });
});

describe("hydrate: a conversation that cannot take another turn", () => {
  /**
   * The stored-id path deliberately does NOT filter by state the way recovery
   * does — this is the tab's own conversation and the admin should see it. What
   * must not survive is the ability to type into it: ENDED and ERROR are
   * terminal, IN_PROGRESS has a turn still executing, and the next send fails
   * at the backend. So the transcript is restored and the composer closes.
   */
  it.each(["ENDED", "ERROR", "EXECUTION_INTERRUPTED"] as const)(
    "restores a %s conversation read-only",
    async (state) => {
      storeId("conv-1");
      h.logs = [{ ...TWO_TURNS, conversationState: state }];

      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.hydrate();
      });

      expect(result.current.messages).toHaveLength(4);
      expect(result.current.isReadOnly).toBe(true);
      expect(result.current.conversationState).toBe(state);
    },
  );

  it("follows an IN_PROGRESS conversation through to its outcome instead of parking it read-only", async () => {
    // A reload while an approved step is running used to land on "This
    // conversation is finished — start a new one", permanently, because
    // nothing watched for the turn to settle: the state the user was in
    // (waiting on the approved step) was lost to the reload. Now the hydrate
    // polls to the settle and re-reads — the answer, or the next approval
    // card, appears exactly as it would have without the reload.
    storeId("conv-1");
    h.logs = [
      // The reload's read: turn still executing.
      { ...TWO_TURNS, conversationState: "IN_PROGRESS" },
      // The follow-through poll (current-step-only) sees the turn pause again.
      { conversationState: "AWAITING_HUMAN", hitlPausedAt: "2026-08-16T10:00:00Z", conversationOutputs: [textOutput("Approve deployAgent?")] },
      // The re-hydrate (full transcript) — three turns now, ending in the ask.
      {
        conversationState: "AWAITING_HUMAN",
        hitlPausedAt: "2026-08-16T10:00:00Z",
        conversationSteps: [step("what is deployed?"), step("and the logs?"), step("deploy it")],
        conversationOutputs: [textOutput("Three agents."), textOutput("All clean."), textOutput("Approve deployAgent?")],
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.isReadOnly).toBe(false);
    expect(result.current.isPaused).toBe(true);
    expect(result.current.isResolvingPause).toBe(false);
    expect(result.current.messages).toHaveLength(6);
    const last = result.current.messages[result.current.messages.length - 1];
    expect(last?.content).toBe("Approve deployAgent?");
    expect(result.current.pausedPlaceholderId).toBe(last?.id);
    expect(h.logs).toHaveLength(0);
  });

  it("an IN_PROGRESS follow-through yields to a newer pick instead of stomping it", async () => {
    // A newer selectConversation installs its OWN controller without aborting
    // the follow-through's; the follow-through must notice it no longer owns
    // the store and NOT overwrite the picked conversation with the old one's
    // settled state — nor clear the pick's own resolving flag.
    //
    // Reads are dispatched by conversation id here (not FIFO): the
    // follow-through's poll and the pick's read genuinely race, and a FIFO
    // queue would make the test hinge on scheduling order rather than on the
    // guard.
    storeId("conv-1");
    const conv1Reads: Array<Partial<SimpleConversationMemorySnapshot>> = [
      { ...TWO_TURNS, conversationState: "IN_PROGRESS" },
      { conversationState: "READY", conversationOutputs: [textOutput("conv-1 settled")] },
      { conversationState: "READY", conversationSteps: [step("stale")], conversationOutputs: [textOutput("conv-1 stale")] },
    ];
    const conv2Read: Partial<SimpleConversationMemorySnapshot> = {
      conversationState: "READY",
      conversationSteps: [step("other")],
      conversationOutputs: [textOutput("conv-2 answer")],
    };
    const { getSimpleConversationLog } = await import("@/lib/api/conversations");
    const shared = vi.mocked(getSimpleConversationLog).getMockImplementation();
    vi.mocked(getSimpleConversationLog).mockImplementation(async (conversationId: string) => {
      if (conversationId === "conv-2") return conv2Read as SimpleConversationMemorySnapshot;
      const next = conv1Reads.shift();
      if (!next) throw new Error("test bug: conv-1 reads exhausted");
      // The follow-through's first poll is the moment the user picks conv-2.
      if (conv1Reads.length === 1) void useOperatorChatStore.getState().selectConversation("conv-2");
      return next as SimpleConversationMemorySnapshot;
    });
    try {
      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.hydrate();
        await new Promise((r) => setTimeout(r, 50));
      });

      expect(result.current.conversationId).toBe("conv-2");
      expect(result.current.messages.map((m) => m.content)).toEqual(["other", "conv-2 answer"]);
      expect(result.current.isResolvingPause).toBe(false);
    } finally {
      // Scoped to this test — the shared FIFO mock serves every other one.
      vi.mocked(getSimpleConversationLog).mockImplementation(shared!);
    }
  });

  it("an IN_PROGRESS follow-through yields to a re-pick of the SAME conversation", async () => {
    // Same id, newer controller: the conversationId guard alone cannot tell the
    // two apart, so this is the case that needs the "is this still my
    // controller?" check. The follow-through's stale re-hydrate must not clear
    // the re-pick's own resolving flag or overwrite its result.
    storeId("conv-1");
    const reads: Array<Partial<SimpleConversationMemorySnapshot>> = [
      { ...TWO_TURNS, conversationState: "IN_PROGRESS" },              // hydrate
      { conversationState: "READY", conversationOutputs: [textOutput("settled")] }, // follow-through poll → re-pick fires here
      { conversationState: "IN_PROGRESS", conversationSteps: [step("q")], conversationOutputs: [textOutput("still running")] }, // re-pick's read: STILL executing
      { conversationState: "READY", conversationSteps: [step("stale")], conversationOutputs: [textOutput("stale re-hydrate")] }, // old follow-through re-hydrate — must be discarded
      // The re-pick's own follow-through poll never resolves in this test:
      // we assert the state while it is still resolving.
    ];
    let pickStarted = false;
    const { getSimpleConversationLog } = await import("@/lib/api/conversations");
    const shared = vi.mocked(getSimpleConversationLog).getMockImplementation();
    vi.mocked(getSimpleConversationLog).mockImplementation(async () => {
      const next = reads.shift();
      if (!next) await new Promise(() => {}); // the re-pick's poll: hang
      if (reads.length === 2 && !pickStarted) {
        pickStarted = true;
        void useOperatorChatStore.getState().selectConversation("conv-1");
      }
      return next as SimpleConversationMemorySnapshot;
    });
    try {
      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.hydrate();
        await new Promise((r) => setTimeout(r, 50));
      });

      // The re-pick owns the screen: its "still running" transcript, and its
      // resolving flag is still up because ITS follow-through is polling. The
      // old follow-through's stale re-hydrate did not land, and its finally
      // did not clear the flag out from under the re-pick.
      expect(result.current.messages.map((m) => m.content)).toEqual(["q", "still running"]);
      expect(result.current.isResolvingPause).toBe(true);
    } finally {
      vi.mocked(getSimpleConversationLog).mockImplementation(shared!);
      useOperatorChatStore.getState().reset();
    }
  });

  it.each(["READY", "AWAITING_HUMAN"] as const)("leaves a %s conversation writable", async (state) => {
    storeId("conv-1");
    h.logs = [{ ...TWO_TURNS, conversationState: state }];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.isReadOnly).toBe(false);
  });

  it("clears read-only when the admin starts a new conversation", async () => {
    storeId("conv-1");
    h.logs = [{ ...TWO_TURNS, conversationState: "ENDED" }];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });
    expect(result.current.isReadOnly).toBe(true);

    await act(async () => {
      result.current.reset();
    });

    expect(result.current.isReadOnly).toBe(false);
  });

  /**
   * A resume can time out locally and still succeed on the server. Keeping the
   * failure banner beside a snapshot the backend now reports as READY would
   * leave a completed approval looking permanently failed.
   */
  it("clears a stale resolveError once the conversation is no longer paused", async () => {
    storeId("conv-1");
    useOperatorChatStore.setState({ resolveError: "Timed out waiting for the resumed turn to finish." });
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.resolveError).toBeNull();
  });

  it("keeps resolveError while the same decision is still outstanding", async () => {
    storeId("conv-1");
    useOperatorChatStore.setState({ resolveError: "Timed out waiting for the resumed turn to finish." });
    h.logs = [
      {
        conversationState: "AWAITING_HUMAN",
        conversationSteps: [step("deploy it")],
        conversationOutputs: [textOutput("I need approval to deploy.")],
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.isPaused).toBe(true);
    expect(result.current.resolveError).toBeTruthy();
  });
});

describe("hydrate: declining to do anything", () => {
  /**
   * The store is shared between the full page and the docked drawer, and both
   * call hydrate on mount. Without the in-flight latch the transcript would be
   * appended twice.
   */
  it("is a no-op while another hydrate is already in flight", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      // Two mounted surfaces, same tick — only one read may happen.
      await Promise.all([result.current.hydrate(), result.current.hydrate()]);
    });

    expect(h.logCalls).toHaveLength(1);
    expect(result.current.messages).toHaveLength(4);
  });

  it("never overwrites a transcript that is already on screen", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    // A second mount later in the session must not re-read or re-append.
    await act(async () => {
      await result.current.hydrate();
    });

    expect(h.logCalls).toHaveLength(1);
    expect(result.current.messages).toHaveLength(4);
  });

  /**
   * A restore is slow; the admin does not wait for it and types. `send` puts
   * its optimistic bubbles up synchronously, so by the time the read resolves
   * the screen belongs to the new turn — and replacing it with a transcript
   * that predates the question would make the admin's own message vanish.
   *
   * Checked AFTER the read, not only before it: the pre-check cannot see a
   * send that had not happened yet when hydrate started.
   */
  it("yields to a turn the admin started while the read was in flight", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];
    h.duringLogRead = () => {
      void useOperatorChatStore.getState().send(config(), "never mind, what about logs?");
    };

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.messages.some((m) => m.content === "never mind, what about logs?")).toBe(true);
    expect(result.current.messages.some((m) => m.content === "what is deployed?")).toBe(false);
  });

  it("does nothing when the operator is not configured", async () => {
    storeId("conv-1");

    const { result } = renderHook(() => useOperatorChat(null));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(h.logCalls).toEqual([]);
  });

  /**
   * `reset()` aborts the in-flight read. Without the abort check the resolved
   * transcript would land in the clean slate the user just asked for — and
   * re-raise a pause they had cleared.
   */
  it("discards a result whose hydrate was reset mid-flight", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];
    h.duringLogRead = () => useOperatorChatStore.getState().reset();

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.messages).toEqual([]);
    expect(result.current.conversationId).toBeNull();
  });
});

describe("hydrate: recovering after a browser restart", () => {
  /**
   * sessionStorage is gone after a restart, so there is no id to read. The
   * operator's newest still-usable conversation is looked up instead — which
   * keeps the storage tab-scoped (see CONVERSATION_STORAGE_KEY) and costs one
   * request.
   */
  it("finds the operator's newest conversation when nothing is stored", async () => {
    h.descriptors = [
      descriptor("conv-old", { lastModifiedOn: 1000 }),
      descriptor("conv-newest", { lastModifiedOn: 5000 }),
      descriptor("conv-middle", { lastModifiedOn: 3000 }),
    ];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(h.descriptorCalls).toEqual([{ limit: 20, agentId: "operator-agent" }]);
    expect(result.current.conversationId).toBe("conv-newest");
    expect(sessionStorage.getItem("eddi.operator.conversationId")).toBe("conv-newest");
  });

  /**
   * Picked from the page rather than trusting position 0: the descriptor
   * endpoint's sort is a per-filter backend setting, not a newest-first
   * contract, and resuming the OLDEST conversation is a bug nobody reports
   * precisely.
   */
  it("picks by timestamp, not by the order the backend happened to return", async () => {
    h.descriptors = [
      descriptor("conv-newest", { lastModifiedOn: 9000 }),
      descriptor("conv-old", { lastModifiedOn: 10 }),
    ];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.conversationId).toBe("conv-newest");
  });

  /**
   * Deactivating the operator ends every active conversation
   * (`endAllActiveConversations`), so after a deactivate/reactivate cycle the
   * newest conversation is always a dead one. Restoring it would show a
   * transcript whose composer fails on the next message.
   */
  it("restores only READY and AWAITING_HUMAN, never a terminal or running one", async () => {
    // ENDED and ERROR are terminal; IN_PROGRESS means a turn is still
    // executing. Restoring any of them puts a live composer over a conversation
    // that rejects the next message — the failure the filter exists to prevent,
    // and one an earlier ENDED-only filter reached through ERROR.
    h.descriptors = [
      descriptor("conv-ended", { lastModifiedOn: 9000, conversationState: "ENDED" }),
      descriptor("conv-error", { lastModifiedOn: 8000, conversationState: "ERROR" }),
      descriptor("conv-running", { lastModifiedOn: 7000, conversationState: "IN_PROGRESS" }),
      descriptor("conv-live", { lastModifiedOn: 100 }),
    ];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.conversationId).toBe("conv-live");
  });

  /**
   * "Start a new conversation" must stay started.
   *
   * `reset()` clears the stored id, and "no stored id" is the exact signal
   * recovery reads as "fresh tab, restore the newest". Nothing ends the
   * conversation server-side, so it stays READY and stays newest — making the
   * discarded conversation the FIRST thing recovery picks. Before the tombstone
   * this took three clicks to reproduce: clear the chat, close the drawer,
   * reopen it, and the whole transcript was back, pause included.
   */
  it("does not resurrect a conversation the admin explicitly cleared", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });
    expect(result.current.messages).toHaveLength(4);

    // The admin presses "Start a new conversation"...
    await act(async () => {
      result.current.reset();
    });
    // ...and the backend still happily offers that conversation back.
    h.descriptors = [descriptor("conv-1", { lastModifiedOn: 9000 })];
    h.logs = [TWO_TURNS];

    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.messages).toEqual([]);
    expect(result.current.conversationId).toBeNull();
    expect(h.descriptorCalls).toEqual([]);
  });

  /** ...but a conversation deliberately adopted afterwards IS restorable again. */
  it("recovers again once a new conversation has been started", async () => {
    useOperatorChatStore.getState().reset();
    await act(async () => {
      await useOperatorChatStore.getState().ensureConversation(config());
    });
    useOperatorChatStore.setState({ conversationId: null });
    h.descriptors = [descriptor("conv-later", { lastModifiedOn: 9000 })];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.conversationId).toBe("conv-later");
  });

  /**
   * The activation probes run against the operator's OWN agent, and
   * `runPostActivationProbes` is fire-and-forget after activation returns — so
   * for ~30 seconds the newest conversation for that agent is a machine one
   * that is about to be ended. A second tab opened in that window used to
   * restore the canary as the admin's own transcript ("List the agents on this
   * platform. Use your tools; do not guess.") and then sit on a dead
   * conversation.
   */
  it("never adopts an activation probe's own conversation", async () => {
    h.descriptors = [
      descriptor("conv-canary", { lastModifiedOn: 9000, userId: OPERATOR_PROBE_USER_ID }),
      descriptor("conv-mine", { lastModifiedOn: 100 }),
    ];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.conversationId).toBe("conv-mine");
  });

  /**
   * This function explicitly refuses to trust the endpoint's ordering — and
   * reading only page 0 quietly depended on exactly that ordering being
   * newest-first, because under an oldest-first sort the real newest
   * conversation sits on the LAST page. With a full first page it keeps
   * reading.
   */
  it("pages past a full first page rather than trusting its order", async () => {
    const firstPage = Array.from({ length: 20 }, (_, i) =>
      descriptor(`old-${i}`, { lastModifiedOn: 100 + i }),
    );
    h.descriptorPages = [firstPage, [descriptor("conv-actually-newest", { lastModifiedOn: 9000 })]];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.conversationId).toBe("conv-actually-newest");
  });

  it("stops at the first short page — the common case stays one request", async () => {
    h.descriptorPages = [[descriptor("conv-only", { lastModifiedOn: 500 })]];
    h.logs = [TWO_TURNS];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(h.descriptorCalls).toHaveLength(1);
    expect(result.current.conversationId).toBe("conv-only");
  });

  it("starts clean when the operator has no usable conversation at all", async () => {
    h.descriptors = [descriptor("conv-dead", { conversationState: "ENDED" })];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });

    expect(result.current.conversationId).toBeNull();
    expect(result.current.messages).toEqual([]);
    expect(h.logCalls).toEqual([]);
    expect(result.current.error).toBeNull();
  });
});

describe("selectConversation: the History tab's row click", () => {
  it("replaces what is on screen with the picked conversation", async () => {
    storeId("conv-1");
    h.logs = [TWO_TURNS, { conversationState: "READY", conversationSteps: [step("older question")], conversationOutputs: [textOutput("older answer")] }];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.hydrate();
    });
    expect(result.current.messages).toHaveLength(4);

    await act(async () => {
      await result.current.selectConversation("conv-older");
    });

    expect(result.current.conversationId).toBe("conv-older");
    expect(result.current.messages.map((m) => m.content)).toEqual(["older question", "older answer"]);
    expect(sessionStorage.getItem("eddi.operator.conversationId")).toBe("conv-older");
  });

  it("restores the pause so a paused conversation can be decided from history", async () => {
    h.logs = [
      {
        conversationState: "AWAITING_HUMAN",
        conversationSteps: [step("deploy it")],
        conversationOutputs: [textOutput("I need approval to deploy.")],
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.selectConversation("conv-paused");
    });

    expect(result.current.isPaused).toBe(true);
    // Null from the snapshot ON PURPOSE: the simple snapshot never carries a
    // reason on the wire; the rendered reason overlays from approval-status.
    expect(result.current.pauseReason).toBeNull();
  });

  /**
   * The admin clicked a row the list said existed. Silently showing an empty
   * chat would read as "this conversation was empty" rather than "it is gone".
   */
  it("reports a failure rather than showing an empty chat", async () => {
    h.logError = Object.assign(new Error("Not Found"), { status: 404 });

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.selectConversation("conv-gone");
    });

    expect(result.current.error).toBeTruthy();
    expect(result.current.conversationId).toBeNull();
    expect(result.current.messages).toEqual([]);
  });
});
