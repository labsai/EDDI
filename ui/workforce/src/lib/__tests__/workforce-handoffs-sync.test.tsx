import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import {
  addHandoff,
  clearWorkforceHandoffs,
  completeHandoff,
  handoffsStorageKey,
  useWorkforceHandoffs,
  type WorkforceHandoff,
} from "@eddi/app-kit/lib/workforce-handoffs";

/**
 * The hand-off record lives in `localStorage` and is read through a store: a
 * write in this tab re-renders readers here, and a write in another tab (the
 * `storage` event, which never fires in the writing tab) re-renders them too.
 */

const PRINCIPAL = "sam";
const KEY = handoffsStorageKey(PRINCIPAL);

function record(id: string, conversationId: string | null = null): WorkforceHandoff {
  return {
    id,
    createdAt: "2026-11-06T10:00:00.000Z",
    from: { agentId: "agent1", agentName: "Ada", conversationId: "conv1", afterStep: 1 },
    to: { kind: "agent", id: "agent2", name: "Bo", conversationId },
    question: `question ${id}`,
  };
}

function Reader() {
  const list = useWorkforceHandoffs(PRINCIPAL);
  return <div data-testid="reader">{list.map((h) => `${h.id}:${h.to.conversationId ?? "-"}`).join(",") || "none"}</div>;
}

/** What another tab does: change storage, then the browser tells this tab. */
function otherTabWrites(value: string | null, key: string | null = KEY) {
  if (key !== null) {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  }
  act(() => {
    window.dispatchEvent(new StorageEvent("storage", { key, newValue: value, storageArea: localStorage }));
  });
}

beforeEach(() => {
  localStorage.clear();
  clearWorkforceHandoffs(); // also empties the store's in-memory cache
});
afterEach(() => vi.restoreAllMocks());

describe("hand-off store reactivity", () => {
  it("re-renders a mounted reader on a same-tab write", () => {
    render(<Reader />);
    expect(screen.getByTestId("reader").textContent).toBe("none");
    let id = "";
    act(() => {
      id = addHandoff(PRINCIPAL, { ...record("x"), id: undefined }).id;
    });
    expect(screen.getByTestId("reader").textContent).toBe(`${id}:-`);
    act(() => completeHandoff(PRINCIPAL, id, "conv9"));
    expect(screen.getByTestId("reader").textContent).toBe(`${id}:conv9`);
  });

  it("re-renders on a storage event for its key written by another tab", () => {
    render(<Reader />);
    otherTabWrites(JSON.stringify([record("remote", "conv7")]));
    expect(screen.getByTestId("reader").textContent).toBe("remote:conv7");
  });

  it("re-renders when another tab removes the key (newValue null) or clears storage", () => {
    localStorage.setItem(KEY, JSON.stringify([record("a")]));
    render(<Reader />);
    // The first read happens at mount, after the item was set.
    expect(screen.getByTestId("reader").textContent).toBe("a:-");
    otherTabWrites(null);
    expect(screen.getByTestId("reader").textContent).toBe("none");

    otherTabWrites(JSON.stringify([record("b")]));
    expect(screen.getByTestId("reader").textContent).toBe("b:-");
    localStorage.clear();
    otherTabWrites(null, null);
    expect(screen.getByTestId("reader").textContent).toBe("none");
  });

  it("builds on the other tab's record instead of overwriting it", () => {
    render(<Reader />);
    otherTabWrites(JSON.stringify([record("remote")]));
    act(() => {
      addHandoff(PRINCIPAL, { ...record("mine"), id: "mine" });
    });
    expect(screen.getByTestId("reader").textContent).toBe("remote:-,mine:-");
  });

  it("ignores storage events for other keys", () => {
    localStorage.setItem(KEY, JSON.stringify([record("a")]));
    render(<Reader />);
    // Change the stored value silently: only an event for the key may refresh the cache.
    localStorage.setItem(KEY, JSON.stringify([record("changed")]));
    otherTabWrites("x", "some-other-key");
    expect(screen.getByTestId("reader").textContent).toBe("a:-");
    otherTabWrites(JSON.stringify([record("changed")]));
    expect(screen.getByTestId("reader").textContent).toBe("changed:-");
  });

  it("tolerates malformed JSON from another tab", () => {
    render(<Reader />);
    otherTabWrites("{not json");
    expect(screen.getByTestId("reader").textContent).toBe("none");
  });

  it("removes its storage listener on unmount", () => {
    const add = vi.spyOn(window, "addEventListener");
    const remove = vi.spyOn(window, "removeEventListener");
    const { unmount } = render(<Reader />);
    expect(add.mock.calls.filter(([type]) => type === "storage")).toHaveLength(1);
    unmount();
    expect(remove.mock.calls.filter(([type]) => type === "storage")).toHaveLength(1);
  });

  it("stays correct when storage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    render(<Reader />);
    expect(screen.getByTestId("reader").textContent).toBe("none");
    act(() => {
      addHandoff(PRINCIPAL, { ...record("kept"), id: "kept" });
    });
    expect(screen.getByTestId("reader").textContent).toBe("kept:-");
  });
});
