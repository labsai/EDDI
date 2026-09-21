import { describe, it, expect } from "vitest";
import { resolveConfigWriteTarget, bodyHasRedactions } from "../config-write-target";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

function preview(overrides: Partial<ResolvedRequestPreview> = {}): ResolvedRequestPreview {
  return {
    method: "PUT",
    uri: "http://localhost:7070/rulestore/rulesets/abc123?version=3",
    queryParams: { version: "3" },
    headers: {},
    body: "{}",
    bodyTruncated: false,
    ...overrides,
  };
}

describe("resolveConfigWriteTarget", () => {
  it("identifies a whole-document write and the base version to compare against", () => {
    // EDDI writes version+1, so the version in the URI is the version currently
    // STORED — the correct left-hand side of the diff.
    const target = resolveConfigWriteTarget(preview());
    expect(target?.resourceType.store).toBe("rulestore");
    expect(target?.resourceType.plural).toBe("rulesets");
    expect(target?.id).toBe("abc123");
    expect(target?.version).toBe(3);
  });

  it("covers every writable extension store", () => {
    const stores: [string, string][] = [
      ["rulestore", "rulesets"],
      ["outputstore", "outputsets"],
      ["propertysetterstore", "propertysetters"],
      ["dictionarystore", "dictionaries"],
      ["apicallstore", "apicalls"],
      ["mcpcallsstore", "mcpcalls"],
    ];
    for (const [store, plural] of stores) {
      const target = resolveConfigWriteTarget(
        preview({ uri: `http://x/${store}/${plural}/id1?version=2` }),
      );
      expect(target, `${store}/${plural}`).not.toBeNull();
      expect(target?.resourceType.store).toBe(store);
    }
  });

  it("ignores anything that is not a PUT", () => {
    expect(resolveConfigWriteTarget(preview({ method: "GET" }))).toBeNull();
    expect(resolveConfigWriteTarget(preview({ method: "POST" }))).toBeNull();
    expect(resolveConfigWriteTarget(preview({ method: "PATCH" }))).toBeNull();
  });

  it("ignores a sub-resource verb, which is not a whole-document replacement", () => {
    // updateResourceUri repoints one reference; diffing it as a document
    // replacement would invent a diff against something it never touches.
    expect(
      resolveConfigWriteTarget(
        preview({ uri: "http://x/agentstore/agents/a1/updateResourceUri?version=1" }),
      ),
    ).toBeNull();
  });

  it("ignores a store it does not recognise", () => {
    expect(
      resolveConfigWriteTarget(preview({ uri: "http://x/somethingstore/things/a1?version=1" })),
    ).toBeNull();
  });

  it("refuses when no usable version is present, rather than guessing one", () => {
    // A diff against the wrong version invents changes, and an approver who
    // trusts it approves on a false picture.
    expect(
      resolveConfigWriteTarget(preview({ uri: "http://x/rulestore/rulesets/a1", queryParams: {} })),
    ).toBeNull();
    expect(
      resolveConfigWriteTarget(
        preview({ uri: "http://x/rulestore/rulesets/a1?version=abc", queryParams: {} }),
      ),
    ).toBeNull();
    expect(
      resolveConfigWriteTarget(
        preview({ uri: "http://x/rulestore/rulesets/a1?version=0", queryParams: {} }),
      ),
    ).toBeNull();
  });

  it("falls back to the URI's own query string when queryParams is empty", () => {
    const target = resolveConfigWriteTarget(
      preview({ uri: "http://x/rulestore/rulesets/a1?version=7", queryParams: {} }),
    );
    expect(target?.version).toBe(7);
  });

  it("tolerates a relative URI and a malformed one", () => {
    expect(
      resolveConfigWriteTarget(preview({ uri: "/rulestore/rulesets/a1?version=4", queryParams: {} }))
        ?.version,
    ).toBe(4);
    expect(resolveConfigWriteTarget(preview({ uri: "::::" }))).toBeNull();
  });

  it("prefers the parsed queryParams over the URI's own query string", () => {
    // queryParams is the authoritative parsed form the backend resolved; the
    // inline query string is only a fallback for a preview that lacked it.
    const target = resolveConfigWriteTarget(
      preview({ uri: "http://x/rulestore/rulesets/a1?version=9", queryParams: { version: "3" } }),
    );
    expect(target?.version).toBe(3);
  });
});

describe("bodyHasRedactions", () => {
  it("detects the marker that makes credential lines diff as false changes", () => {
    expect(bodyHasRedactions('{"apiKey":"<REDACTED>"}')).toBe(true);
    expect(bodyHasRedactions('{"name":"ordinary"}')).toBe(false);
    expect(bodyHasRedactions(null)).toBe(false);
    expect(bodyHasRedactions(undefined)).toBe(false);
  });
});
