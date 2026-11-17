import { describe, it, expect } from "vitest";
import {
  MAX_VALUE_CHARS,
  filterMemoryRows,
  memoryKeyLabel,
  memoryValueText,
  memoryVisibility,
  toMemoryRows,
} from "@/lib/memory-entries";
import type { UserMemoryEntry } from "@eddi/app-kit/lib/api/user-memory";

const entry = (over: Partial<UserMemoryEntry>): UserMemoryEntry => ({
  id: "e",
  userId: "u",
  key: "k",
  value: "v",
  category: "fact",
  visibility: "self",
  ...over,
});

describe("memory-entries", () => {
  it("reads visibility case-insensitively and calls anything else unknown", () => {
    expect(memoryVisibility("SELF")).toBe("self");
    expect(memoryVisibility("group")).toBe("group");
    expect(memoryVisibility("Global")).toBe("global");
    expect(memoryVisibility("team")).toBe("unknown");
    expect(memoryVisibility(undefined)).toBe("unknown");
  });

  it("shows strings as they are and anything else as compact JSON, cut when long", () => {
    expect(memoryValueText("plain")).toBe("plain");
    expect(memoryValueText({ a: 1 })).toBe('{"a":1}');
    expect(memoryValueText(42)).toBe("42");
    expect(memoryValueText(null)).toBe("");
    const long = memoryValueText("x".repeat(MAX_VALUE_CHARS + 10));
    expect(long).toHaveLength(MAX_VALUE_CHARS + 1);
    expect(long.endsWith("…")).toBe(true);
  });

  it("counts the GDPR rows instead of listing them", () => {
    const { rows, reservedCount } = toMemoryRows(
      [entry({ key: "_gdpr_restricted" }), entry({ key: "name" })],
      new Map(),
      new Map(),
    );
    expect(rows.map((r) => r.entry.key)).toEqual(["name"]);
    expect(reservedCount).toBe(1);
  });

  it("names agents and teams from the catalog, falling back to their ids", () => {
    const { rows } = toMemoryRows(
      [
        entry({ id: "a", sourceAgentId: "ag1", visibility: "group", groupIds: ["g1", "g2"] }),
        entry({ id: "b", sourceAgentId: "  " }),
      ],
      new Map([["ag1", "Helper"]]),
      new Map([["g1", "Team One"]]),
    );
    expect(rows.find((r) => r.id === "a")).toMatchObject({ agentName: "Helper", teamNames: ["Team One", "g2"] });
    expect(rows.find((r) => r.id === "b")?.agentName).toBeNull();
  });

  it("sorts newest first, by update and else by creation", () => {
    const { rows } = toMemoryRows(
      [
        entry({ id: "old", updatedAt: "2026-01-01T00:00:00Z" }),
        entry({ id: "created-only", createdAt: "2026-06-01T00:00:00Z" }),
        entry({ id: "new", updatedAt: "2026-10-01T00:00:00Z" }),
        entry({ id: "undated" }),
      ],
      new Map(),
      new Map(),
    );
    expect(rows.map((r) => r.id)).toEqual(["new", "created-only", "old", "undated"]);
  });

  it("searches key, value, agent, category and team, ignoring case", () => {
    const { rows } = toMemoryRows(
      [
        entry({ id: "1", key: "home_city", value: "Graz", sourceAgentId: "a", category: "fact" }),
        entry({ id: "2", key: "tone", value: "formal", visibility: "group", groupIds: ["g"], category: "preference" }),
      ],
      new Map([["a", "Travel Agent"]]),
      new Map([["g", "Sales"]]),
    );
    const ids = (q: string) => filterMemoryRows(rows, q).map((r) => r.id);
    expect(ids("")).toEqual(["1", "2"]);
    expect(ids("GRAZ")).toEqual(["1"]);
    expect(ids("home_city")).toEqual(["1"]);
    expect(ids("travel")).toEqual(["1"]);
    expect(ids("preference")).toEqual(["2"]);
    expect(ids("sales")).toEqual(["2"]);
    expect(ids("zzz")).toEqual([]);
  });

  it("turns a key into words", () => {
    expect(memoryKeyLabel("preferred_language")).toBe("preferred language");
    expect(memoryKeyLabel("a-b")).toBe("a b");
    expect(memoryKeyLabel("___")).toBe("___");
  });
});
