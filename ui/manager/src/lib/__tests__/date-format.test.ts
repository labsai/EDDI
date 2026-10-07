import { describe, it, expect } from "vitest";
import {
  toIsoWithOffset,
  zonedLocalInputToIso,
  isoToZonedLocalInput,
} from "@/lib/date-format";

describe("zonedLocalInputToIso", () => {
  it("interprets the wall clock in the given zone, not the browser's", () => {
    // Vienna is UTC+2 in summer, UTC+1 in winter; New York is UTC-4 / UTC-5.
    expect(zonedLocalInputToIso("2026-07-01T09:00", "Europe/Vienna")).toBe("2026-07-01T07:00:00.000Z");
    expect(zonedLocalInputToIso("2026-12-01T09:00", "Europe/Vienna")).toBe("2026-12-01T08:00:00.000Z");
    expect(zonedLocalInputToIso("2026-07-01T09:00", "America/New_York")).toBe("2026-07-01T13:00:00.000Z");
    expect(zonedLocalInputToIso("2026-07-01T09:00", "UTC")).toBe("2026-07-01T09:00:00.000Z");
  });

  it("handles a DST boundary day on both sides of the change", () => {
    // Europe/Vienna springs forward 2026-03-29 02:00 -> 03:00.
    expect(zonedLocalInputToIso("2026-03-29T01:30", "Europe/Vienna")).toBe("2026-03-29T00:30:00.000Z");
    expect(zonedLocalInputToIso("2026-03-29T04:00", "Europe/Vienna")).toBe("2026-03-29T02:00:00.000Z");
  });

  it("rejects empty, malformed and unknown-zone input", () => {
    expect(zonedLocalInputToIso("", "UTC")).toBeNull();
    expect(zonedLocalInputToIso("not a date", "UTC")).toBeNull();
    expect(zonedLocalInputToIso("2026-07-01T09:00", "Mars/Olympus")).toBeNull();
  });

  it("round-trips through isoToZonedLocalInput", () => {
    for (const tz of ["UTC", "Asia/Kolkata", "America/Los_Angeles", "Australia/Sydney"]) {
      const iso = zonedLocalInputToIso("2026-10-04T18:45", tz)!;
      expect(isoToZonedLocalInput(iso, tz)).toBe("2026-10-04T18:45");
    }
  });
});

describe("toIsoWithOffset", () => {
  it("is an ISO-8601 string with a numeric offset naming the same instant", () => {
    const ms = Date.UTC(2026, 9, 4, 11, 11, 5, 123);
    const out = toIsoWithOffset(ms);
    expect(out).toMatch(/^2026-10-0[3-5]T\d{2}:\d{2}:05\.123[+-]\d{2}:\d{2}$/);
    expect(new Date(out).getTime()).toBe(ms);
  });

  it("is empty for a missing or invalid timestamp", () => {
    expect(toIsoWithOffset(undefined)).toBe("");
    expect(toIsoWithOffset("garbage")).toBe("");
  });
});
