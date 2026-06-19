import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getCoordinatorStatus,
  getDeadLetters,
  replayDeadLetter,
  discardDeadLetter,
  purgeDeadLetters,
} from "../coordinator";

describe("coordinator API", () => {
  describe("getCoordinatorStatus", () => {
    it("fetches coordinator status", async () => {
      const result = await getCoordinatorStatus();
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/administration/coordinator/status", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(getCoordinatorStatus()).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  describe("getDeadLetters", () => {
    it("fetches dead letters", async () => {
      const result = await getDeadLetters();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });
  });

  describe("replayDeadLetter", () => {
    it("replays a dead letter entry", async () => {
      await expect(replayDeadLetter("entry1")).resolves.toBeUndefined();
    });
  });

  describe("discardDeadLetter", () => {
    it("discards a dead letter entry", async () => {
      await expect(discardDeadLetter("entry1")).resolves.toBeUndefined();
    });
  });

  describe("purgeDeadLetters", () => {
    it("purges all dead letters and returns count", async () => {
      const result = await purgeDeadLetters();
      expect(result).toBeDefined();
    });
  });
});
