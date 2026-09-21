import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { MAX_GROUP_ATTACHMENTS } from "@/lib/api/groups";
import { MAX_ATTACHMENT_BYTES } from "@/lib/api/attachments";
import { useGroupAttachmentStaging } from "@/hooks/use-group-attachment-staging";

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}));

/**
 * The caps a discussion's attachments are staged under. Both are enforced
 * before the bytes reach the wire, and both used to be spendable by files that
 * were never staged at all.
 */

function file(name: string, sizeBytes: number, type = "text/plain"): File {
  const f = new File([new ArrayBuffer(8)], name, { type });
  Object.defineProperty(f, "size", { value: sizeBytes });
  Object.defineProperty(f, "lastModified", { value: 1 });
  return f;
}

/** N distinct small files, enough to fill the count cap. */
function fill(n: number) {
  return Array.from({ length: n }, (_, i) => file(`f${i}.txt`, 10));
}

describe("useGroupAttachmentStaging", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does not let a rejected file consume the last free slot", async () => {
    // Truncating the selection to the remaining room BEFORE validating it meant
    // an oversized file took the slot and the legal file behind it was never
    // looked at, with no message saying why.
    const { result } = renderHook(() => useGroupAttachmentStaging(true));

    await act(async () => {
      await result.current.addFiles(fill(MAX_GROUP_ATTACHMENTS - 1));
    });
    await waitFor(() => {
      expect(result.current.attachments).toHaveLength(MAX_GROUP_ATTACHMENTS - 1);
    });

    await act(async () => {
      await result.current.addFiles([
        file("huge.bin", MAX_ATTACHMENT_BYTES + 1),
        file("small.txt", 10),
      ]);
    });

    await waitFor(() => {
      expect(result.current.attachments).toHaveLength(MAX_GROUP_ATTACHMENTS);
    });
    expect(result.current.attachments.map((a) => a.fileName)).toContain("small.txt");
    expect(result.current.attachments.map((a) => a.fileName)).not.toContain("huge.bin");
  });

  it("stops at the count cap", async () => {
    const { result } = renderHook(() => useGroupAttachmentStaging(true));

    await act(async () => {
      await result.current.addFiles(fill(MAX_GROUP_ATTACHMENTS + 5));
    });

    await waitFor(() => {
      expect(result.current.attachments).toHaveLength(MAX_GROUP_ATTACHMENTS);
    });
  });

  it("drops everything staged the moment attaching is disallowed", async () => {
    // A continuation is a 400 server-side, so anything staged has to go rather
    // than sit in the composer as a request the backend will refuse.
    const { result, rerender } = renderHook(
      ({ enabled }) => useGroupAttachmentStaging(enabled),
      { initialProps: { enabled: true } },
    );

    await act(async () => {
      await result.current.addFiles([file("a.txt", 10)]);
    });
    await waitFor(() => expect(result.current.attachments).toHaveLength(1));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.attachments).toHaveLength(0));
    expect(result.current.toRefs()).toBeNull();
  });
});
