import { describe, it, expect } from "vitest";
import {
  parseCron,
  isValidCron,
  describeCron,
  nextCronFires,
  cronMinIntervalSeconds,
  isoToLocalInput,
  localInputToIso,
  listTimeZones,
  CRON_PRESETS,
  MIN_INTERVAL_SECONDS,
  parseInstant,
  fireLogDurationMs,
} from "../schedules";

// The backend (Quarkus write-dates-as-timestamps) serializes Instant as
// FRACTIONAL EPOCH SECONDS. parseInstant must handle that plus ISO / millis.
describe("parseInstant", () => {
  it("parses fractional epoch SECONDS (the real backend format)", () => {
    const d = parseInstant(1719964800.123);
    expect(d?.getTime()).toBe(1719964800123);
  });
  it("parses a numeric-seconds string", () => {
    expect(parseInstant("1719964800")?.getTime()).toBe(1719964800000);
  });
  it("parses an ISO-8601 string (tolerated for @JsonFormat fields / mocks)", () => {
    expect(parseInstant("2026-07-01T09:00:00.000Z")?.getTime()).toBe(
      Date.parse("2026-07-01T09:00:00.000Z")
    );
  });
  it("treats an already-millis number as millis (no double *1000)", () => {
    expect(parseInstant(1719964800123)?.getTime()).toBe(1719964800123);
  });
  it("returns null for missing/blank/unparseable", () => {
    expect(parseInstant(null)).toBeNull();
    expect(parseInstant(undefined)).toBeNull();
    expect(parseInstant("")).toBeNull();
    expect(parseInstant("not-a-date")).toBeNull();
  });
  it("fireLogDurationMs computes ms from fractional-second timestamps", () => {
    expect(
      fireLogDurationMs({
        scheduleId: "s",
        status: "COMPLETED",
        fireTime: 1719964800,
        startedAt: 1719964800,
        completedAt: 1719964802.5,
      })
    ).toBe(2500);
  });
});

describe("parseCron / isValidCron", () => {
  it("accepts a standard weekday expression", () => {
    expect(isValidCron("0 9 * * MON-FRI")).toBe(true);
    const p = parseCron("0 9 * * MON-FRI")!;
    expect([...p.minutes]).toEqual([0]);
    expect([...p.hours]).toEqual([9]);
    expect([...p.dows].sort()).toEqual([1, 2, 3, 4, 5]);
    expect(p.dowRestricted).toBe(true);
    expect(p.domRestricted).toBe(false);
  });

  it("expands step and list fields", () => {
    const p = parseCron("*/15 * * * *")!;
    expect([...p.minutes]).toEqual([0, 15, 30, 45]);
    const list = parseCron("0,30 8-10 * * *")!;
    expect([...list.minutes]).toEqual([0, 30]);
    expect([...list.hours]).toEqual([8, 9, 10]);
  });

  it("treats dow 7 and 0 both as Sunday", () => {
    const p = parseCron("0 0 * * 7")!;
    expect([...p.dows]).toEqual([0]);
  });

  it("resolves named months", () => {
    const p = parseCron("0 0 1 JAN,JUL *")!;
    expect([...p.months].sort((a, b) => a - b)).toEqual([1, 7]);
  });

  it("rejects malformed expressions", () => {
    expect(parseCron("")).toBeNull();
    expect(parseCron("* * * *")).toBeNull(); // 4 fields
    expect(parseCron("60 * * * *")).toBeNull(); // minute out of range
    expect(parseCron("* 24 * * *")).toBeNull(); // hour out of range
    expect(parseCron("* * * * BADDAY")).toBeNull();
    expect(parseCron("*/0 * * * *")).toBeNull(); // zero step
  });
});

describe("nextCronFires", () => {
  it("computes the next daily fires in UTC", () => {
    const from = new Date("2026-07-20T08:00:00Z"); // Monday
    const fires = nextCronFires("0 9 * * *", 3, from, "UTC");
    expect(fires).toHaveLength(3);
    expect(fires[0]!.toISOString()).toBe("2026-07-20T09:00:00.000Z");
    expect(fires[1]!.toISOString()).toBe("2026-07-21T09:00:00.000Z");
    expect(fires[2]!.toISOString()).toBe("2026-07-22T09:00:00.000Z");
  });

  it("skips a fire already past on the first day", () => {
    const from = new Date("2026-07-20T10:00:00Z");
    const fires = nextCronFires("0 9 * * *", 1, from, "UTC");
    expect(fires[0]!.toISOString()).toBe("2026-07-21T09:00:00.000Z");
  });

  it("honours weekday restrictions", () => {
    const from = new Date("2026-07-18T00:00:00Z"); // Saturday
    const fires = nextCronFires("0 9 * * MON-FRI", 1, from, "UTC");
    // Next weekday is Monday 2026-07-20
    expect(fires[0]!.toISOString()).toBe("2026-07-20T09:00:00.000Z");
  });

  it("evaluates wall-clock in the requested timezone", () => {
    const from = new Date("2026-07-20T00:00:00Z");
    // 09:00 in Vienna (UTC+2 in July) == 07:00Z
    const fires = nextCronFires("0 9 * * *", 1, from, "Europe/Vienna");
    expect(fires[0]!.toISOString()).toBe("2026-07-20T07:00:00.000Z");
  });
});

describe("cronMinIntervalSeconds", () => {
  it("returns 900 for every-15-minutes", () => {
    expect(cronMinIntervalSeconds("*/15 * * * *", "UTC")).toBe(900);
  });

  it("returns 60 for every-minute (the floor)", () => {
    expect(cronMinIntervalSeconds("* * * * *", "UTC")).toBe(MIN_INTERVAL_SECONDS);
  });

  it("returns a full day for a daily schedule", () => {
    expect(cronMinIntervalSeconds("0 9 * * *", "UTC")).toBe(86400);
  });
});

describe("describeCron", () => {
  it("describes common shapes", () => {
    expect(describeCron("* * * * *")).toBe("Every minute");
    expect(describeCron("*/15 * * * *")).toBe("Every 15 minutes");
    expect(describeCron("0 * * * *")).toBe("Hourly at :00");
    expect(describeCron("30 14 * * *")).toBe("At 14:30");
    expect(describeCron("0 9 * * MON-FRI")).toContain("At 09:00");
    expect(describeCron("0 9 * * MON-FRI")).toContain("Monday");
  });

  it("returns null for invalid input", () => {
    expect(describeCron("nonsense")).toBeNull();
  });

  it("describes every preset without throwing", () => {
    for (const preset of CRON_PRESETS) {
      expect(describeCron(preset.expression)).toBeTruthy();
    }
  });
});

describe("describeCron localization", () => {
  it("routes fixed phrases through the provided translator", () => {
    const t = (key: string) => `«${key}»`;
    expect(describeCron("* * * * *", t)).toBe("«schedules.cronEveryMinute»");
    expect(describeCron("*/15 * * * *", t)).toBe("«schedules.cronEveryNMinutes»");
    expect(describeCron("30 14 * * *", t)).toBe("«schedules.cronAtTime»");
  });

  it("interpolates runtime values into the translated phrase", () => {
    const t = (_key: string, def: string, vars?: Record<string, string>) =>
      def.replace(/\{\{(\w+)\}\}/g, (_m, k) => vars?.[k] ?? "");
    expect(describeCron("*/15 * * * *", t)).toBe("Every 15 minutes");
    expect(describeCron("30 14 * * *", t)).toBe("At 14:30");
  });

  it("localizes weekday names via Intl when a language is passed", () => {
    const t = (_k: string, d: string, v?: Record<string, string>) =>
      d.replace(/\{\{(\w+)\}\}/g, (_m, k) => v?.[k] ?? "");
    // 0 9 * * 1 → Monday; de weekday = "Montag", fr = "lundi"
    expect(describeCron("0 9 * * 1", t, "de")).toContain("Montag");
    expect(describeCron("0 9 * * 1", t, "fr")).toContain("lundi");
  });

  it("stays English when no translator is passed (backward compatible)", () => {
    expect(describeCron("0 9 * * 1")).toContain("Monday");
    expect(describeCron("0 0 1 JAN *")).toContain("January");
  });
});

describe("datetime-local conversion", () => {
  it("round-trips through local input format", () => {
    const local = isoToLocalInput("2026-07-20T09:30:00.000Z");
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    const iso = localInputToIso(local);
    expect(iso).toBe("2026-07-20T09:30:00.000Z");
  });

  it("handles empty / invalid values", () => {
    expect(isoToLocalInput(undefined)).toBe("");
    expect(isoToLocalInput("not-a-date")).toBe("");
    expect(localInputToIso("")).toBeNull();
    expect(localInputToIso("not-a-date")).toBeNull();
  });
});

describe("listTimeZones", () => {
  it("returns a non-empty list that includes UTC", () => {
    const zones = listTimeZones();
    expect(zones.length).toBeGreaterThan(0);
    expect(zones).toContain("UTC");
  });
});
