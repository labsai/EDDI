import { describe, it, expect, vi } from "vitest";
import { localId } from "@/lib/local-id";

describe("localId", () => {
  it("is unique even within the same millisecond", () => {
    vi.spyOn(Date, "now").mockReturnValue(1_700_000_000_000);
    const ids = Array.from({ length: 50 }, () => localId("local"));
    expect(new Set(ids).size).toBe(50);
    vi.restoreAllMocks();
  });

  it("keeps the prefix", () => {
    expect(localId("err")).toMatch(/^err-/);
  });
});
