import { describe, it, expect } from "vitest";

import {
  getUserConversation,
  createUserConversation,
  deleteUserConversation,
} from "../user-conversations";

describe("user-conversations API", () => {
  describe("getUserConversation", () => {
    it("fetches user conversation binding", async () => {
      const result = await getUserConversation("booking_request", "user1");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("intent");
      expect(result).toHaveProperty("conversationId");
    });
  });

  describe("createUserConversation", () => {
    it("creates a user conversation binding", async () => {
      const result = await createUserConversation(
        "test-intent",
        "user1",
        {
          intent: "test-intent",
          userId: "user1",
          environment: "production",
          agentId: "agent1",
          conversationId: "conv-new",
        }
      );
      expect(result).toBeDefined();
      expect(result.location).toBeDefined();
    });
  });

  describe("deleteUserConversation", () => {
    it("deletes a user conversation binding", async () => {
      await expect(
        deleteUserConversation("test-intent", "user1")
      ).resolves.toBeUndefined();
    });
  });
});
