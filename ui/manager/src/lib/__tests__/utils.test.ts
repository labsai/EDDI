import { describe, it, expect, vi, afterEach } from "vitest";
import { cn, formatRelativeTime, statusConfig, hashColor, getInitials, isValidUrl, formatDuration } from "@/lib/utils";

/** What this environment's CLDR data calls "n units ago" in narrow English. */
function ago(n: number, unit: Intl.RelativeTimeFormatUnit): string {
  return new Intl.RelativeTimeFormat("en", { style: "narrow", numeric: "always" }).format(-n, unit);
}


describe("cn", () => {
  it("merges class names", () => {
    expect(cn("foo", "bar")).toBe("foo bar");
  });

  it("handles conditional classes", () => {
    const isHidden = false;
    expect(cn("base", isHidden && "hidden", "visible")).toBe("base visible");
  });

  it("merges tailwind classes", () => {
    // twMerge should resolve conflicts
    expect(cn("px-2", "px-4")).toBe("px-4");
  });

  it("handles undefined and null inputs", () => {
    expect(cn("foo", undefined, null, "bar")).toBe("foo bar");
  });

  it("returns empty string with no inputs", () => {
    expect(cn()).toBe("");
  });
});

describe("formatRelativeTime", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns 'just now' for recent timestamps", () => {
    expect(formatRelativeTime(Date.now() - 5000)).toBe("just now");
  });

  it("returns minutes ago", () => {
    // 3 minutes ago
    expect(formatRelativeTime(Date.now() - 3 * 60 * 1000)).toBe(ago(3, "minute"));
  });

  it("returns hours ago", () => {
    // 2 hours ago
    expect(formatRelativeTime(Date.now() - 2 * 60 * 60 * 1000)).toBe(ago(2, "hour"));
  });

  it("returns days ago", () => {
    // 5 days ago
    expect(formatRelativeTime(Date.now() - 5 * 24 * 60 * 60 * 1000)).toBe(ago(5, "day"));
  });

  it("speaks the language on screen, not always English", async () => {
    // It used to build "5m ago" by hand in every locale.
    const { default: i18n } = await import("@/i18n/config");
    await i18n.changeLanguage("de");
    try {
      expect(formatRelativeTime(Date.now() - 5 * 60 * 1000)).not.toBe(ago(5, "minute"));
      expect(formatRelativeTime(Date.now() - 5 * 60 * 1000)).toBe(
        new Intl.RelativeTimeFormat("de", { style: "narrow" }).format(-5, "minute"),
      );
      expect(formatRelativeTime(Date.now())).toBe("gerade eben");
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  it("returns 'just now' for current time", () => {
    expect(formatRelativeTime(Date.now())).toBe("just now");
  });

  it("returns '1m ago' for exactly 60 seconds", () => {
    expect(formatRelativeTime(Date.now() - 60 * 1000)).toBe(ago(1, "minute"));
  });

  it("returns '1h ago' for exactly 60 minutes", () => {
    expect(formatRelativeTime(Date.now() - 60 * 60 * 1000)).toBe(ago(1, "hour"));
  });

  it("returns '1d ago' for exactly 24 hours", () => {
    expect(formatRelativeTime(Date.now() - 24 * 60 * 60 * 1000)).toBe(ago(1, "day"));
  });

  it("returns '—' for 0 timestamp", () => {
    expect(formatRelativeTime(0)).toBe("—");
  });

  it("returns '—' for NaN timestamp", () => {
    expect(formatRelativeTime(NaN)).toBe("—");
  });

  it("returns '—' for undefined timestamp", () => {
    expect(formatRelativeTime(undefined as unknown as number)).toBe("—");
  });
});

describe("statusConfig", () => {
  it("has READY config", () => {
    expect(statusConfig.READY!.label).toBe("Deployed");
    expect(statusConfig.READY!.color).toContain("emerald");
  });

  it("has IN_PROGRESS config", () => {
    expect(statusConfig.IN_PROGRESS!.label).toBe("Deploying");
    expect(statusConfig.IN_PROGRESS!.color).toContain("amber");
  });

  it("has ERROR config", () => {
    expect(statusConfig.ERROR!.label).toBe("Error");
    expect(statusConfig.ERROR!.color).toContain("destructive");
  });

  it("has NOT_FOUND config", () => {
    expect(statusConfig.NOT_FOUND!.label).toBe("Not deployed");
    expect(statusConfig.NOT_FOUND!.color).toContain("muted");
  });

  it("all statuses have dot property", () => {
    for (const key of Object.keys(statusConfig)) {
      expect(statusConfig[key]!.dot).toBeTruthy();
    }
  });
});

describe("hashColor", () => {
  it("returns a valid Tailwind bg color class", () => {
    const result = hashColor("test-string");
    expect(result).toMatch(/^bg-[a-z]+-500$/);
  });

  it("is deterministic — same input produces same output", () => {
    const a = hashColor("hello");
    const b = hashColor("hello");
    expect(a).toBe(b);
  });

  it("different inputs can produce different colors", () => {
    // Not guaranteed, but "a" and "z" should hash differently
    const a = hashColor("a");
    const b = hashColor("z");
    // We just verify they are both valid colors
    expect(a).toMatch(/^bg-/);
    expect(b).toMatch(/^bg-/);
  });

  it("handles empty string", () => {
    const result = hashColor("");
    expect(result).toMatch(/^bg-[a-z]+-500$/);
  });

  it("handles long strings", () => {
    const result = hashColor("a".repeat(1000));
    expect(result).toMatch(/^bg-[a-z]+-500$/);
  });
});

describe("getInitials", () => {
  it("extracts initials from two-word name", () => {
    expect(getInitials("John Doe")).toBe("JD");
  });

  it("extracts single initial from single name", () => {
    expect(getInitials("Alice")).toBe("A");
  });

  it("limits to 2 initials for three-word names", () => {
    expect(getInitials("John Michael Doe")).toBe("JM");
  });

  it("uppercases the initials", () => {
    expect(getInitials("john doe")).toBe("JD");
  });

  it("handles extra whitespace", () => {
    expect(getInitials("  John   Doe  ")).toBe("JD");
  });

  it("returns empty string for empty input", () => {
    expect(getInitials("")).toBe("");
  });

  it("handles single character name", () => {
    expect(getInitials("X")).toBe("X");
  });
});

describe("isValidUrl", () => {
  it("returns true for http URLs", () => {
    expect(isValidUrl("http://example.com")).toBe(true);
  });

  it("returns true for https URLs", () => {
    expect(isValidUrl("https://example.com")).toBe(true);
  });

  it("returns true for URLs with paths", () => {
    expect(isValidUrl("https://example.com/path/to/resource")).toBe(true);
  });

  it("returns true for URLs with query params", () => {
    expect(isValidUrl("https://example.com?foo=bar&baz=1")).toBe(true);
  });

  it("returns false for ftp URLs", () => {
    expect(isValidUrl("ftp://example.com")).toBe(false);
  });

  it("returns false for non-URL strings", () => {
    expect(isValidUrl("not a url")).toBe(false);
  });

  it("returns false for empty string", () => {
    expect(isValidUrl("")).toBe(false);
  });

  it("returns false for relative paths", () => {
    expect(isValidUrl("/path/to/resource")).toBe(false);
  });

  it("returns false for javascript: protocol", () => {
    expect(isValidUrl("javascript:alert(1)")).toBe(false);
  });
});

describe("formatDuration", () => {
  it("returns '<1ms' for 0", () => {
    expect(formatDuration(0)).toBe("<1ms");
  });

  it("returns '<1ms' for negative values", () => {
    expect(formatDuration(-5)).toBe("<1ms");
  });

  it("returns '<1ms' for sub-millisecond values", () => {
    expect(formatDuration(0.5)).toBe("<1ms");
  });

  it("returns milliseconds for values under 1000", () => {
    expect(formatDuration(42)).toBe("42ms");
    expect(formatDuration(999)).toBe("999ms");
  });

  it("returns seconds for values >= 1000", () => {
    expect(formatDuration(1000)).toBe("1.00s");
    expect(formatDuration(1500)).toBe("1.50s");
    expect(formatDuration(12345)).toBe("12.35s");
  });

  it("rounds milliseconds to nearest integer", () => {
    expect(formatDuration(42.7)).toBe("43ms");
  });

  it("returns '<1ms' for NaN", () => {
    expect(formatDuration(NaN)).toBe("<1ms");
  });

  it("returns '<1ms' for Infinity", () => {
    expect(formatDuration(Infinity)).toBe("<1ms");
  });

  it("returns '<1ms' for -Infinity", () => {
    expect(formatDuration(-Infinity)).toBe("<1ms");
  });

  it("returns '1ms' for exactly 1", () => {
    expect(formatDuration(1)).toBe("1ms");
  });
});
