import { describe, it, expect } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { agentKeys, agentWriteInvalidations, groupKeys } from "../query-keys";

describe("agentKeys", () => {
  it("nests under the agents root so a broad invalidate sweeps the lists", () => {
    expect(agentKeys.descriptors(20, 0, "")[0]).toBe(agentKeys.all[0]);
    expect(agentKeys.descriptorsInfinite("")[0]).toBe(agentKeys.all[0]);
  });

  it("gives one id one descriptor key", () => {
    expect(agentKeys.descriptor("abc")).toEqual(["agent-descriptor", "abc"]);
  });

  it("keeps the prompt key stable whether or not a version is supplied", () => {
    expect(agentKeys.prompt("abc")).toEqual(["agent-prompt", "abc"]);
    expect(agentKeys.prompt("abc", 3)).toEqual(["agent-prompt", "abc", 3]);
    // The versionless key must PREFIX the versioned one, so invalidating the
    // former clears every version rather than missing them all.
    expect(agentKeys.prompt("abc", 3).slice(0, 2)).toEqual(agentKeys.prompt("abc"));
  });
});

describe("agentWriteInvalidations", () => {
  it("covers the detail, the descriptor and the list", () => {
    const keys = agentWriteInvalidations("abc");
    expect(keys).toContainEqual(agentKeys.detail("abc"));
    expect(keys).toContainEqual(agentKeys.descriptor("abc"));
    expect(keys).toContainEqual(agentKeys.all);
  });

  it("actually invalidates what the Workforce panels read", async () => {
    // Regression, driven through a real QueryClient rather than by comparing
    // arrays. The details panel and the thread header used to read
    // ["agent-descriptor-direct", id] — a second name for the same request that
    // no writer invalidated — so saving an agent left both showing stale data
    // until the entry aged out. Every reader now resolves the same key, so a
    // write reaches them.
    const client = new QueryClient();
    const readerKey = agentKeys.descriptor("abc");

    client.setQueryData(readerKey, [{ name: "old name" }]);
    expect(client.getQueryState(readerKey)?.isInvalidated).toBe(false);

    for (const queryKey of agentWriteInvalidations("abc")) {
      await client.invalidateQueries({ queryKey });
    }

    expect(client.getQueryState(readerKey)?.isInvalidated).toBe(true);
  });

  it("does not invalidate a different agent's descriptor", async () => {
    const client = new QueryClient();
    const other = agentKeys.descriptor("zzz");
    client.setQueryData(other, [{ name: "untouched" }]);

    for (const queryKey of agentWriteInvalidations("abc")) {
      await client.invalidateQueries({ queryKey });
    }

    expect(client.getQueryState(other)?.isInvalidated).toBe(false);
  });

  it("sweeps every agent list query through the shared root", async () => {
    const client = new QueryClient();
    const list = agentKeys.descriptors(20, 0, "");
    const infinite = agentKeys.descriptorsInfinite("");
    client.setQueryData(list, []);
    client.setQueryData(infinite, []);

    await client.invalidateQueries({ queryKey: agentKeys.all });

    expect(client.getQueryState(list)?.isInvalidated).toBe(true);
    expect(client.getQueryState(infinite)?.isInvalidated).toBe(true);
  });
});

describe("groupKeys", () => {
  it("scopes one group's conversations under the shared root", async () => {
    const client = new QueryClient();
    const scoped = groupKeys.conversationsFor("g1");
    client.setQueryData(scoped, []);

    await client.invalidateQueries({ queryKey: groupKeys.conversations });

    expect(client.getQueryState(scoped)?.isInvalidated).toBe(true);
  });
});
