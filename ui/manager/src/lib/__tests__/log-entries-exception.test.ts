import { describe, it, expect } from "vitest";
import {
  logEntryKey,
  historyEntryKey,
  newByMultiplicity,
} from "@eddi/app-kit/lib/log-entries";

describe("log entry identity includes the exception", () => {
  const base = { timestamp: 1, level: "ERROR", loggerName: "l", message: "m" };

  it("keeps two live rows that differ only by exception", () => {
    const a = { ...base, exception: "java.io.IOException: a" };
    const b = { ...base, exception: "java.io.IOException: b" };
    expect(logEntryKey(a)).not.toBe(logEntryKey(b));
    expect(newByMultiplicity([a], [a, b], logEntryKey)).toEqual([b]);
  });

  it("keeps two history rows that differ only by exception", () => {
    const a = { ...base, exception: "x" };
    const b = { ...base, exception: "y" };
    expect(historyEntryKey(a)).not.toBe(historyEntryKey(b));
    expect(newByMultiplicity([a], [b], historyEntryKey)).toEqual([b]);
  });

  it("still drops a true replay", () => {
    const a = { ...base, exception: "x" };
    expect(newByMultiplicity([a], [{ ...a }], logEntryKey)).toEqual([]);
  });
});
