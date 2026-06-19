import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getAllMemories,
  searchMemories,
  getMemoriesByCategory,
  getMemoryByKey,
  upsertMemory,
  deleteMemory,
  deleteAllForUser,
  countMemories,
} from "../user-memory";

describe("user-memory API", () => {
  describe("getAllMemories", () => {
    it("fetches all memories for a user", async () => {
      const result = await getAllMemories("user1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });
  });

  describe("searchMemories", () => {
    it("searches memories by query", async () => {
      const result = await searchMemories("user1", "test-query");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });
  });

  describe("getMemoriesByCategory", () => {
    it("fetches memories by category", async () => {
      const result = await getMemoriesByCategory("user1", "preferences");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });
  });

  describe("getMemoryByKey", () => {
    it("fetches a memory by key", async () => {
      server.use(
        http.get("*/usermemorystore/memories/:userId/key/:key", () =>
          HttpResponse.json({
            userId: "user1",
            key: "fav-color",
            value: "blue",
            category: "preferences",
            visibility: "public",
          })
        )
      );
      const result = await getMemoryByKey("user1", "fav-color");
      expect(result).toBeDefined();
      expect(result?.key).toBe("fav-color");
    });
  });

  describe("upsertMemory", () => {
    it("creates or updates a memory entry", async () => {
      await expect(
        upsertMemory({
          userId: "user1",
          key: "test-key",
          value: "test-val",
          category: "test",
          visibility: "public",
        })
      ).resolves.toBeUndefined();
    });
  });

  describe("deleteMemory", () => {
    it("deletes a memory entry by ID", async () => {
      await expect(deleteMemory("entry-123")).resolves.toBeUndefined();
    });
  });

  describe("deleteAllForUser", () => {
    it("deletes all memories for a user", async () => {
      await expect(deleteAllForUser("user1")).resolves.toBeUndefined();
    });
  });

  describe("countMemories", () => {
    it("returns memory count as number", async () => {
      const result = await countMemories("user1");
      expect(typeof result).toBe("number");
    });

    it("handles raw number response", async () => {
      server.use(
        http.get("*/usermemorystore/memories/:userId/count", () =>
          HttpResponse.json(42)
        )
      );
      const result = await countMemories("user1");
      expect(result).toBe(42);
    });

    it("handles { count: N } response", async () => {
      server.use(
        http.get("*/usermemorystore/memories/:userId/count", () =>
          HttpResponse.json({ count: 7 })
        )
      );
      const result = await countMemories("user1");
      expect(result).toBe(7);
    });

    it("returns 0 when response has no count", async () => {
      server.use(
        http.get("*/usermemorystore/memories/:userId/count", () =>
          HttpResponse.json({})
        )
      );
      const result = await countMemories("user1");
      expect(result).toBe(0);
    });
  });
});
