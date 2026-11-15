import { describe, it, expect } from "vitest";
import { isValidNotifyAddress, mergeNotify, parseNotifyRecipients, slackIntegrationProblem } from "@/lib/hitl-notify";
import { MAX_NOTIFY_INTEGRATION_NAME, MAX_NOTIFY_RECIPIENTS } from "@/lib/api/hitl";

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

  it("refuses URL syntax after a Unicode domain instead of parsing it away", () => {
    // new URL() would drop the path, query, fragment or port and report only the host.
    for (const tail of ["/path", "?x=1", "#frag", ":25", "\\evil", "%2f"]) {
      expect(isValidNotifyAddress(`ops@bücher.example${tail}`)).toBe(false);
    }
  });

  it("refuses the characters whose IDNA 2003 and 2008 conversions differ (the server uses IDN.toASCII)", () => {
    // sharp s, final sigma, zero-width non-joiner / joiner: the platform keeps them,
    // Java's IDN.toASCII maps or drops them, so the two would disagree on the domain.
    for (const domain of ["faß.example", "σς.example", "a‌b.example", "a‍b.example"]) {
      expect(isValidNotifyAddress(`ops@${domain}`)).toBe(false);
    }
    // the punycode spelling is plain ASCII and stays valid
    expect(isValidNotifyAddress("ops@xn--fa-hia.example")).toBe(true);
  });

  it("treats the Unicode and punycode spellings of one domain as a duplicate", () => {
    const parsed = parseNotifyRecipients("ops@bücher.example, OPS@xn--bcher-kva.example");
    expect(parsed.error).toBe("duplicate");
    expect(parsed.index).toBe(1);
  });
});

describe("slackIntegrationProblem — mirrors HitlConfigValidation.validateNotifySlack", () => {
  it("accepts a plain name and refuses blank, '|' and over-long ones", () => {
    expect(slackIntegrationProblem(" ops-slack ")).toBeNull();
    expect(slackIntegrationProblem("  ")).toBe("empty");
    expect(slackIntegrationProblem("ops|slack")).toBe("pipe");
    expect(slackIntegrationProblem("x".repeat(MAX_NOTIFY_INTEGRATION_NAME))).toBeNull();
    expect(slackIntegrationProblem("x".repeat(MAX_NOTIFY_INTEGRATION_NAME + 1))).toBe("tooLong");
  });
});

describe("mergeNotify", () => {
  const email = { recipients: ["ops@example.com"] };
  const slack = { integration: "ops-slack" };

  it("replaces one channel and keeps the other", () => {
    expect(mergeNotify({ email }, { slack })).toEqual({ email, slack });
    expect(mergeNotify({ email, slack }, { email: null })).toEqual({ slack });
    expect(mergeNotify({ email, slack }, { slack: null })).toEqual({ email });
  });

  it("is null once neither channel is left", () => {
    expect(mergeNotify({ slack }, { slack: null })).toBeNull();
    expect(mergeNotify(null, { email: null })).toBeNull();
    expect(mergeNotify(undefined, { email })).toEqual({ email });
  });
});
