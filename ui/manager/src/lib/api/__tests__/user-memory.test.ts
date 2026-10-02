import { describe, it, expect } from "vitest";
import {
  getAllMemories,
  deleteMemory,
  deleteAllForUser,
} from "../user-memory";

describe("user-memory API", () => {
  describe("getAllMemories", () => {
    it("fetches all memories for a user", async () => {
      const result = await getAllMemories("user1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
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
});
