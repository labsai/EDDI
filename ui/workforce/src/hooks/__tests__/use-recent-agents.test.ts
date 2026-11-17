import { beforeEach, describe, expect, it } from "vitest";
import { markAgentUsed, sortByRecentUse } from "@/hooks/use-recent-agents";

const agents = [{ id: "a" }, { id: "b" }, { id: "c" }];

describe("sortByRecentUse", () => {
  it("puts recently used agents first, newest use first", () => {
    expect(sortByRecentUse(agents, { c: 200, b: 100 }).map((x) => x.id)).toEqual(["c", "b", "a"]);
  });

  it("keeps the incoming order for agents never used", () => {
    expect(sortByRecentUse(agents, {}).map((x) => x.id)).toEqual(["a", "b", "c"]);
  });
});

describe("markAgentUsed", () => {
  beforeEach(() => localStorage.clear());

  it("stores a timestamp per agent and ignores empty ids", () => {
    markAgentUsed("b");
    markAgentUsed("");
    const stored = JSON.parse(localStorage.getItem("workforce-recent-agents") ?? "{}");
    expect(Object.keys(stored)).toEqual(["b"]);
  });
});
