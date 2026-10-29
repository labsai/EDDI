import { describe, it, expect } from "vitest";
import { isValidNotifyAddress, parseNotifyRecipients } from "@/lib/hitl-notify";
import { MAX_NOTIFY_RECIPIENTS } from "@/lib/api/hitl";

describe("parseNotifyRecipients — mirrors HitlConfigValidation.validateNotify", () => {
  it("splits on commas, semicolons, spaces and new lines", () => {
    expect(parseNotifyRecipients("a@example.com, b@example.com;c@example.com\nd@example.com")).toEqual({
      recipients: ["a@example.com", "b@example.com", "c@example.com", "d@example.com"],
      error: null,
      index: null,
    });
  });

  it("an empty list cannot be saved", () => {
    expect(parseNotifyRecipients("  , \n").error).toBe("empty");
  });

  it("caps the list at the backend's maximum", () => {
    const max = Array.from({ length: MAX_NOTIFY_RECIPIENTS }, (_, i) => `r${i}@example.com`);
    expect(parseNotifyRecipients(max.join(",")).error).toBeNull();
    expect(parseNotifyRecipients([...max, "extra@example.com"].join(",")).error).toBe("tooMany");
  });

  it("names the first invalid entry", () => {
    const parsed = parseNotifyRecipients("ok@example.com, Ops <ops@example.com>");
    expect(parsed.error).toBe("invalid");
    expect(parsed.index).toBe(1);
  });

  it("finds duplicates ignoring case", () => {
    const parsed = parseNotifyRecipients("ops@example.com, OPS@Example.com");
    expect(parsed.error).toBe("duplicate");
    expect(parsed.index).toBe(1);
  });
});

describe("isValidNotifyAddress", () => {
  it("accepts a plain ASCII address", () => {
    expect(isValidNotifyAddress("first.last+tag@mail.example.co.uk")).toBe(true);
  });

  it("refuses what the backend refuses: no domain dot, numeric TLD, non-ASCII, brackets", () => {
    for (const bad of ["ops@localhost", "ops@10.0.0.1", "jürgen@example.com", "<ops@example.com>", "ops", "@example.com"]) {
      expect(isValidNotifyAddress(bad)).toBe(false);
    }
  });
});

describe("internationalised domains — the server converts the domain to ASCII", () => {
  it("accepts an IDN domain, in Unicode or punycode form", () => {
    expect(isValidNotifyAddress("ops@bücher.example")).toBe(true);
    expect(isValidNotifyAddress("ops@xn--bcher-kva.example")).toBe(true);
    expect(parseNotifyRecipients("ops@bücher.example").error).toBeNull();
  });

  it("still refuses a non-ASCII local part and a malformed IDN domain", () => {
    expect(isValidNotifyAddress("jürgen@bücher.example")).toBe(false);
    expect(isValidNotifyAddress("ops@bücher")).toBe(false);
    expect(isValidNotifyAddress("ops@bü cher.example")).toBe(false);
  });

  it("treats the Unicode and punycode spellings of one domain as a duplicate", () => {
    const parsed = parseNotifyRecipients("ops@bücher.example, OPS@xn--bcher-kva.example");
    expect(parsed.error).toBe("duplicate");
    expect(parsed.index).toBe(1);
  });
});
