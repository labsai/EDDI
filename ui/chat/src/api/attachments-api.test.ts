/* ──────────────────────────────────────────────
   Attachments — context construction and upload
   ────────────────────────────────────────────── */

import { describe, it, expect, afterEach } from "vitest";
import {
  buildAttachmentContext,
  uploadAttachment,
  MAX_ATTACHMENTS_PER_TURN,
} from "./attachments-api";
import { setBaseUrl } from "./http";
import { captureFetch, mockFetchResponse } from "@/test-utils/sse";
import type { AttachmentResult } from "./attachments-api";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

const att = (n: number): AttachmentResult => ({
  storageRef: `ref-${n}`,
  fileName: `file-${n}.pdf`,
  mimeType: "application/pdf",
  sizeBytes: 10,
});

describe("buildAttachmentContext", () => {
  it("keys each attachment with the attachment_ prefix the backend scans for", () => {
    // AttachmentContextExtractor skips every context key not prefixed
    // "attachment_". Without this the file never reaches the model.
    const context = buildAttachmentContext([att(0), att(1)]);

    expect(Object.keys(context)).toEqual(["attachment_0", "attachment_1"]);
  });

  it("sends an object-valued context carrying storageRef and fileName", () => {
    // The extractor requires Context.value to be a Map containing storageRef;
    // a string value is skipped outright.
    const context = buildAttachmentContext([att(0)]);

    expect(context.attachment_0).toEqual({
      type: "object",
      value: { storageRef: "ref-0", fileName: "file-0.pdf" },
    });
  });

  it("omits an empty fileName so the backend's own fallback can fire", () => {
    // The upload endpoint answers with "" when the store has no filename, and
    // the extractor only backfills the stored name when the key is ABSENT
    // (getFileName() == null). Passing "" through suppresses the fallback and
    // the model is handed a file the forwarder labels "unnamed".
    const context = buildAttachmentContext([{ ...att(0), fileName: "" }]);

    expect(context.attachment_0).toEqual({
      type: "object",
      value: { storageRef: "ref-0" },
    });
  });

  it("omits mimeType — the server resolves it from validated store metadata", () => {
    const value = buildAttachmentContext([att(0)]).attachment_0.value as Record<
      string,
      unknown
    >;

    expect(value).not.toHaveProperty("mimeType");
    expect(value).not.toHaveProperty("sizeBytes");
  });

  it("caps at the backend's per-turn limit", () => {
    const many = Array.from({ length: 9 }, (_, i) => att(i));

    const context = buildAttachmentContext(many);

    expect(Object.keys(context)).toHaveLength(MAX_ATTACHMENTS_PER_TURN);
    expect(MAX_ATTACHMENTS_PER_TURN).toBe(5);
  });

  it("returns an empty context for no attachments", () => {
    expect(buildAttachmentContext([])).toEqual({});
  });
});

describe("uploadAttachment", () => {
  it("posts multipart to the conversation-scoped attachment endpoint", async () => {
    setBaseUrl("");
    const { calls } = captureFetch(
      201,
      '{"storageRef":"r1","fileName":"a.pdf","mimeType":"application/pdf","sizeBytes":3}',
    );

    await uploadAttachment("conv-1", new File(["abc"], "a.pdf"));

    expect(calls[0].url).toBe("/conversations/conv-1/attachments");
    expect(calls[0].init?.method).toBe("POST");
  });

  it("surfaces the server's rejection code on an oversized file", async () => {
    setBaseUrl("");
    mockFetchResponse(413, '{"code":"ATTACHMENT_TOO_LARGE"}');

    await expect(
      uploadAttachment("conv-1", new File(["abc"], "a.pdf")),
    ).rejects.toMatchObject({ status: 413 });
  });
});
