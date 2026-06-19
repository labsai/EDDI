import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  deleteUserData,
  exportUserData,
  restrictProcessing,
  unrestrictProcessing,
  isProcessingRestricted,
} from "../gdpr";

describe("gdpr API", () => {
  describe("deleteUserData", () => {
    it("deletes user data and returns result", async () => {
      const result = await deleteUserData("user-123");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("userId");
      expect(result).toHaveProperty("memoriesDeleted");
    });

    it("handles API error", async () => {
      server.use(
        http.delete("*/admin/gdpr/:userId", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(deleteUserData("user-fail")).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  describe("exportUserData", () => {
    it("exports user data", async () => {
      const result = await exportUserData("user-123");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("userId");
      expect(result).toHaveProperty("memories");
      expect(result).toHaveProperty("conversations");
    });
  });

  describe("restrictProcessing", () => {
    it("restricts processing for a user", async () => {
      await expect(
        restrictProcessing("user-123")
      ).resolves.toBeUndefined();
    });
  });

  describe("unrestrictProcessing", () => {
    it("removes processing restriction", async () => {
      await expect(
        unrestrictProcessing("user-123")
      ).resolves.toBeUndefined();
    });
  });

  describe("isProcessingRestricted", () => {
    it("checks if processing is restricted", async () => {
      const result = await isProcessingRestricted("user-123");
      expect(typeof result).toBe("boolean");
    });
  });
});
