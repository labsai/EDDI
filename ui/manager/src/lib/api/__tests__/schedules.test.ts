import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getSchedules,
  getSchedule,
  createSchedule,
  updateSchedule,
  deleteSchedule,
  enableSchedule,
  disableSchedule,
  fireNow,
  retryDeadLetter,
  dismissDeadLetter,
  getFireLogs,
  getFailedFires,
} from "../schedules";

describe("schedules API", () => {
  // ─── getSchedules ───────────────────────────────────────────────
  describe("getSchedules", () => {
    it("fetches all schedules", async () => {
      const result = await getSchedules();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("fetches schedules filtered by agentId", async () => {
      const result = await getSchedules("agent1");
      expect(result).toBeDefined();
    });
  });

  // ─── getSchedule ───────────────────────────────────────────────
  describe("getSchedule", () => {
    it("fetches a single schedule", async () => {
      const result = await getSchedule("sched1");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("name");
    });

    it("handles 404", async () => {
      server.use(
        http.get("*/schedulestore/schedules/:id", () =>
          HttpResponse.json({ message: "Not Found" }, { status: 404 })
        )
      );
      await expect(getSchedule("nonexistent")).rejects.toMatchObject({
        status: 404,
      });
    });
  });

  // ─── createSchedule ────────────────────────────────────────────
  describe("createSchedule", () => {
    it("creates a schedule and returns location", async () => {
      const result = await createSchedule({
        name: "Test Schedule",
        triggerType: "CRON",
        agentId: "agent1",
        agentVersion: 1,
        environment: "production",
        message: "Hello",
        enabled: false,
        fireStatus: "PENDING",
        failCount: 0,
      });
      expect(result).toBeDefined();
      expect(result.location).toBeDefined();
    });
  });

  // ─── updateSchedule ────────────────────────────────────────────
  describe("updateSchedule", () => {
    it("updates a schedule", async () => {
      await expect(
        updateSchedule("sched1", { name: "Updated" })
      ).resolves.toBeUndefined();
    });
  });

  // ─── deleteSchedule ────────────────────────────────────────────
  describe("deleteSchedule", () => {
    it("deletes a schedule", async () => {
      await expect(deleteSchedule("sched1")).resolves.toBeUndefined();
    });
  });

  // ─── enableSchedule ────────────────────────────────────────────
  describe("enableSchedule", () => {
    it("enables a schedule", async () => {
      await expect(enableSchedule("sched1")).resolves.toBeUndefined();
    });
  });

  // ─── disableSchedule ───────────────────────────────────────────
  describe("disableSchedule", () => {
    it("disables a schedule", async () => {
      await expect(disableSchedule("sched1")).resolves.toBeUndefined();
    });
  });

  // ─── fireNow ───────────────────────────────────────────────────
  describe("fireNow", () => {
    it("fires a schedule immediately", async () => {
      await expect(fireNow("sched1")).resolves.toBeUndefined();
    });
  });

  // ─── retryDeadLetter ───────────────────────────────────────────
  describe("retryDeadLetter", () => {
    it("retries a dead-lettered schedule", async () => {
      await expect(retryDeadLetter("sched1")).resolves.toBeUndefined();
    });
  });

  // ─── dismissDeadLetter ─────────────────────────────────────────
  describe("dismissDeadLetter", () => {
    it("dismisses a dead letter", async () => {
      await expect(dismissDeadLetter("sched1")).resolves.toBeUndefined();
    });
  });

  // ─── getFireLogs ───────────────────────────────────────────────
  describe("getFireLogs", () => {
    it("fetches fire logs with default limit", async () => {
      const result = await getFireLogs("sched1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("fetches fire logs with custom limit", async () => {
      const result = await getFireLogs("sched1", 50);
      expect(result).toBeDefined();
    });
  });

  // ─── getFailedFires ────────────────────────────────────────────
  describe("getFailedFires", () => {
    it("fetches failed fires with default limit", async () => {
      const result = await getFailedFires();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("fetches failed fires with custom limit", async () => {
      const result = await getFailedFires(10);
      expect(result).toBeDefined();
    });
  });
});
