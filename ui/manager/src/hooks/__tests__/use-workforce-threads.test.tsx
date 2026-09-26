import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { useWorkforceThreads } from "@/hooks/use-workforce-threads";
import { useTemplates } from "@/hooks/use-templates";
import { AuthContext, GUEST_CONTEXT } from "@/components/auth/auth-context";
import { clearUserScopedStorage } from "@/lib/user-storage";

/**
 * Thread bookkeeping for the Workforce 1:1 view, persisted to localStorage.
 * It decides whether opening an advisor resumes an existing conversation or
 * starts a new one, so a lost or mismatched entry silently strands a thread.
 */

const STORAGE_KEY = "workforce-threads";
const read = () => JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]");

beforeEach(() => {
  localStorage.clear();
});

describe("useWorkforceThreads", () => {
  it("starts empty when nothing is stored", () => {
    const { result } = renderHook(() => useWorkforceThreads());
    expect(result.current.threads).toEqual([]);
    expect(result.current.getThread("b1", "m1")).toBeUndefined();
  });

  it("registers a thread and persists it", () => {
    const { result } = renderHook(() => useWorkforceThreads());

    act(() => {
      result.current.registerThread({
        boardId: "b1",
        memberId: "m1",
        memberName: "Ana",
        conversationId: "c1",
      });
    });

    expect(result.current.getThread("b1", "m1")).toMatchObject({
      boardId: "b1",
      memberId: "m1",
      conversationId: "c1",
    });
    expect(read()).toHaveLength(1);
  });

  it("replaces the conversation when the same member is registered again", () => {
    // Re-registering must not stack duplicates, or getThread would resolve to a
    // stale conversation and the user would resume the wrong one.
    const { result } = renderHook(() => useWorkforceThreads());

    act(() => {
      result.current.registerThread({
        boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1",
      });
    });
    act(() => {
      result.current.registerThread({
        boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c2",
      });
    });

    expect(result.current.threads).toHaveLength(1);
    expect(result.current.getThread("b1", "m1")?.conversationId).toBe("c2");
  });

  it("keys threads by board AND member, not member alone", () => {
    // The same advisor can sit on several task forces; collapsing them would
    // cross-wire one board's conversation into another.
    const { result } = renderHook(() => useWorkforceThreads());

    act(() => {
      result.current.registerThread({
        boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1",
      });
      result.current.registerThread({
        boardId: "b2", memberId: "m1", memberName: "Ana", conversationId: "c2",
      });
    });

    expect(result.current.getThread("b1", "m1")?.conversationId).toBe("c1");
    expect(result.current.getThread("b2", "m1")?.conversationId).toBe("c2");
  });

  it("returns only the requested board's threads", () => {
    const { result } = renderHook(() => useWorkforceThreads());

    act(() => {
      result.current.registerThread({
        boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1",
      });
      result.current.registerThread({
        boardId: "b1", memberId: "m2", memberName: "Bo", conversationId: "c2",
      });
      result.current.registerThread({
        boardId: "b2", memberId: "m3", memberName: "Cy", conversationId: "c3",
      });
    });

    expect(result.current.getThreadsForBoard("b1").map((t) => t.memberId).sort())
      .toEqual(["m1", "m2"]);
    expect(result.current.getThreadsForBoard("b2")).toHaveLength(1);
    expect(result.current.getThreadsForBoard("nope")).toEqual([]);
  });

  it("bumps lastActivity, and ignores an unknown thread", () => {
    const { result } = renderHook(() => useWorkforceThreads());

    act(() => {
      result.current.registerThread({
        boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1",
      });
    });
    const before = result.current.getThread("b1", "m1")!.lastActivity;

    act(() => {
      result.current.updateActivity("b1", "m1");
    });
    expect(result.current.getThread("b1", "m1")!.lastActivity).toBeGreaterThanOrEqual(before);

    // A no-op rather than creating a phantom entry.
    act(() => {
      result.current.updateActivity("b1", "does-not-exist");
    });
    expect(result.current.threads).toHaveLength(1);
  });

  it("removes a thread and leaves the others", () => {
    const { result } = renderHook(() => useWorkforceThreads());

    act(() => {
      result.current.registerThread({
        boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1",
      });
      result.current.registerThread({
        boardId: "b1", memberId: "m2", memberName: "Bo", conversationId: "c2",
      });
    });
    act(() => {
      result.current.removeThread("b1", "m1");
    });

    expect(result.current.getThread("b1", "m1")).toBeUndefined();
    expect(result.current.getThread("b1", "m2")).toBeDefined();
    expect(read()).toHaveLength(1);
  });

  it("rehydrates from localStorage on mount", () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify([
        { boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1", lastActivity: 1 },
      ]),
    );
    const { result } = renderHook(() => useWorkforceThreads());
    expect(result.current.getThread("b1", "m1")?.conversationId).toBe("c1");
  });

  it("survives corrupt or foreign localStorage content", () => {
    // A single bad key must not white-screen the Workforce app, and entries
    // missing required fields are dropped rather than trusted.
    for (const bad of [
      "not json at all",
      JSON.stringify({ notAnArray: true }),
      JSON.stringify([{ memberId: "m1" }, null, 42, { boardId: "b1" }]),
    ]) {
      localStorage.setItem(STORAGE_KEY, bad);
      const { result } = renderHook(() => useWorkforceThreads());
      expect(result.current.threads).toEqual([]);
    }
  });
});

/**
 * Per-user storage. The keys were global, so on a shared browser the next user
 * to sign in saw — and could reopen — the previous user's advisor threads and
 * saved templates.
 */
describe("Workforce storage is per signed-in user", () => {
  function signedInAs(username: string) {
    return ({ children }: { children: ReactNode }) =>
      createElement(
        AuthContext.Provider,
        {
          value: {
            ...GUEST_CONTEXT,
            method: "keycloak",
            user: { username, firstName: "", lastName: "", email: "", fullName: "" },
          },
        },
        children,
      );
  }

  const thread = { boardId: "b1", memberId: "m1", memberName: "Ana", conversationId: "c1" };

  it("does not show one user's threads to another", () => {
    const alice = renderHook(() => useWorkforceThreads(), { wrapper: signedInAs("alice") });
    act(() => alice.result.current.registerThread(thread));
    expect(localStorage.getItem("workforce-threads:alice")).not.toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();

    const bob = renderHook(() => useWorkforceThreads(), { wrapper: signedInAs("bob") });
    expect(bob.result.current.threads).toEqual([]);

    const aliceAgain = renderHook(() => useWorkforceThreads(), { wrapper: signedInAs("alice") });
    expect(aliceAgain.result.current.getThread("b1", "m1")).toMatchObject({ conversationId: "c1" });
  });

  it("does not show one user's templates to another", () => {
    const alice = renderHook(() => useTemplates(), { wrapper: signedInAs("alice") });
    act(() => {
      alice.result.current.saveTemplate({
        name: "Mine",
        description: "",
        style: "ROUND_TABLE",
        members: [],
        maxRounds: 2,
      });
    });
    const bob = renderHook(() => useTemplates(), { wrapper: signedInAs("bob") });
    expect(bob.result.current.templates).toEqual([]);
    const aliceAgain = renderHook(() => useTemplates(), { wrapper: signedInAs("alice") });
    expect(aliceAgain.result.current.templates.map((t) => t.name)).toEqual(["Mine"]);
  });

  it("keeps the plain key when auth is disabled", () => {
    const { result } = renderHook(() => useWorkforceThreads());
    act(() => result.current.registerThread(thread));
    expect(read()).toHaveLength(1);
  });

  it("clearUserScopedStorage removes threads in every scoping, and nothing else", () => {
    localStorage.setItem("workforce-threads", "[]");
    localStorage.setItem("workforce-threads:alice", "[]");
    localStorage.setItem("workforce-templates:alice", "[]");
    localStorage.setItem("eddi-theme", "dark");
    clearUserScopedStorage();
    expect(localStorage.getItem("workforce-threads")).toBeNull();
    expect(localStorage.getItem("workforce-threads:alice")).toBeNull();
    expect(localStorage.getItem("workforce-templates:alice")).toBe("[]");
    expect(localStorage.getItem("eddi-theme")).toBe("dark");
  });
});
