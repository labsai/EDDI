import { describe, it, expect, afterEach } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { api } from "../../api-client";
import {
  uploadAttachment,
  listAttachments,
  deleteAttachment,
  deleteAllAttachments,
  downloadAttachment,
  getAttachmentDownloadUrl,
  buildAttachmentContext,
  isImageMime,
  formatBytes,
  AttachmentError,
  MAX_ATTACHMENT_BYTES,
  MAX_ATTACHMENTS_PER_TURN,
  type AttachmentRef,
} from "../attachments";

describe("attachments API", () => {
  describe("uploadAttachment", () => {
    it("uploads a file and returns the rich result", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json(
            {
              storageRef: "ref-123",
              fileName: "document.pdf",
              mimeType: "application/pdf",
              sizeBytes: 1024,
              conversationId: "conv1",
              forwardableInline: true,
            },
            { status: 201 },
          ),
        ),
      );
      const file = new File(["test content"], "document.pdf", {
        type: "application/pdf",
      });
      const result = await uploadAttachment("conv1", file);
      expect(result.storageRef).toBe("ref-123");
      expect(result.fileName).toBe("document.pdf");
      expect(result.forwardableInline).toBe(true);
    });

    it("normalizes the lowercase `filename` field from the backend", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json(
            { storageRef: "ref-9", filename: "photo.png", mimeType: "image/png", sizeBytes: 10 },
            { status: 201 },
          ),
        ),
      );
      const result = await uploadAttachment("conv1", new File(["x"], "photo.png"));
      expect(result.fileName).toBe("photo.png");
    });

    it("rejects oversized files client-side without a request", async () => {
      const bigFile = { name: "big.bin", size: MAX_ATTACHMENT_BYTES + 1, type: "application/octet-stream" } as File;
      await expect(uploadAttachment("conv1", bigFile)).rejects.toMatchObject({
        code: "ATTACHMENT_TOO_LARGE",
      });
    });

    it("surfaces the backend error code and message", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json(
            { error: "MIME type not allowed", code: "ATTACHMENT_REJECTED" },
            { status: 400 },
          ),
        ),
      );
      const file = new File(["x"], "note.exe", { type: "application/x-msdownload" });
      await expect(uploadAttachment("conv1", file)).rejects.toMatchObject({
        code: "ATTACHMENT_REJECTED",
        message: "MIME type not allowed",
        status: 400,
      });
      await expect(uploadAttachment("conv1", file)).rejects.toBeInstanceOf(AttachmentError);
    });

    it("maps a network failure to AttachmentError(status 0)", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () => HttpResponse.error()),
      );
      await expect(uploadAttachment("conv1", new File(["x"], "a.txt"))).rejects.toMatchObject({
        status: 0,
      });
    });

    it("falls back to statusText on a non-JSON error body", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () =>
          new HttpResponse("<html>oops</html>", {
            status: 500,
            headers: { "Content-Type": "text/html" },
          }),
        ),
      );
      await expect(uploadAttachment("conv1", new File(["x"], "a.txt"))).rejects.toMatchObject({
        status: 500,
        code: undefined,
      });
    });
  });

  describe("downloadAttachment / getAttachmentDownloadUrl", () => {
    it("builds an encoded download URL", () => {
      const url = getAttachmentDownloadUrl("conv 1", "gridfs://a/b");
      expect(url).toContain(encodeURIComponent("conv 1"));
      expect(url).toContain(encodeURIComponent("gridfs://a/b"));
      expect(url).not.toMatch(/gridfs:\/\//);
    });

    it("downloads the raw bytes as a Blob", async () => {
      server.use(
        http.get("*/conversations/:conversationId/attachments/:storageRef", () =>
          new HttpResponse(new Uint8Array([1, 2, 3]), {
            status: 200,
            headers: { "Content-Type": "application/pdf" },
          }),
        ),
      );
      const blob = await downloadAttachment("conv1", "ref-1");
      expect(blob.size).toBe(3);
    });

    it("throws AttachmentError when the download is denied", async () => {
      server.use(
        http.get("*/conversations/:conversationId/attachments/:storageRef", () =>
          HttpResponse.json({ error: "denied", code: "ATTACHMENT_ACCESS_DENIED" }, { status: 403 }),
        ),
      );
      await expect(downloadAttachment("conv1", "ref-1")).rejects.toMatchObject({ status: 403 });
    });
  });

  describe("listAttachments", () => {
    it("returns attachment metadata", async () => {
      server.use(
        http.get("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json([
            { storageRef: "a1", filename: "a.pdf", mimeType: "application/pdf", sizeBytes: 5 },
          ]),
        ),
      );
      const list = await listAttachments("conv1");
      expect(list).toHaveLength(1);
      expect(list[0]?.storageRef).toBe("a1");
    });
  });

  describe("list/delete error paths", () => {
    it("listAttachments throws AttachmentError on a failed response", async () => {
      server.use(
        http.get("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json({ error: "boom" }, { status: 500 }),
        ),
      );
      await expect(listAttachments("conv1")).rejects.toMatchObject({ status: 500 });
      await expect(listAttachments("conv1")).rejects.toBeInstanceOf(AttachmentError);
    });

    it("deleteAttachment throws AttachmentError when access is denied", async () => {
      server.use(
        http.delete("*/conversations/:conversationId/attachments/:storageRef", () =>
          HttpResponse.json({ error: "denied", code: "ATTACHMENT_ACCESS_DENIED" }, { status: 403 }),
        ),
      );
      await expect(deleteAttachment("conv1", "a1")).rejects.toMatchObject({
        status: 403,
        code: "ATTACHMENT_ACCESS_DENIED",
      });
    });
  });

  describe("delete", () => {
    it("deletes a single attachment", async () => {
      server.use(
        http.delete("*/conversations/:conversationId/attachments/:storageRef", () =>
          HttpResponse.json({ storageRef: "a1", deleted: true }),
        ),
      );
      await expect(deleteAttachment("conv1", "a1")).resolves.toBeUndefined();
    });

    it("deletes all attachments and returns the count", async () => {
      server.use(
        http.delete("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json({ conversationId: "conv1", deletedCount: 3 }),
        ),
      );
      await expect(deleteAllAttachments("conv1")).resolves.toBe(3);
    });

    it("returns 0 when delete-all responds with an empty/non-JSON body", async () => {
      server.use(
        http.delete("*/conversations/:conversationId/attachments", () =>
          new HttpResponse(null, { status: 200 }),
        ),
      );
      await expect(deleteAllAttachments("conv1")).resolves.toBe(0);
    });
  });

  describe("buildAttachmentContext", () => {
    it("builds attachment_* keys with storageRef + fileName", () => {
      const refs: AttachmentRef[] = [
        { storageRef: "ref-a", fileName: "a.png", mimeType: "image/png" },
        { storageRef: "ref-b", fileName: "b.pdf", mimeType: "application/pdf" },
      ];
      const ctx = buildAttachmentContext(refs);
      expect(ctx.attachment_0).toEqual({
        type: "object",
        value: { storageRef: "ref-a", fileName: "a.png" },
      });
      expect(ctx.attachment_1?.value.storageRef).toBe("ref-b");
    });

    it("caps at the per-turn limit", () => {
      const refs: AttachmentRef[] = Array.from({ length: MAX_ATTACHMENTS_PER_TURN + 3 }, (_, i) => ({
        storageRef: `ref-${i}`,
        fileName: `f${i}.png`,
        mimeType: "image/png",
      }));
      const ctx = buildAttachmentContext(refs);
      expect(Object.keys(ctx)).toHaveLength(MAX_ATTACHMENTS_PER_TURN);
    });

    it("omits fileName when absent", () => {
      const ctx = buildAttachmentContext([{ storageRef: "r", fileName: "", mimeType: "image/png" }]);
      expect(ctx.attachment_0?.value).toEqual({ storageRef: "r" });
    });

    it("returns an empty map for no attachments", () => {
      expect(buildAttachmentContext([])).toEqual({});
    });
  });

  describe("auth (works with and without a bearer token)", () => {
    afterEach(() => api.clearAuthToken());

    it("attaches the bearer token to uploads when auth is configured", async () => {
      api.setAuthToken("tok-123");
      let auth: string | null = "UNSET";
      server.use(
        http.post("*/conversations/:conversationId/attachments", ({ request }) => {
          auth = request.headers.get("Authorization");
          return HttpResponse.json(
            { storageRef: "r", fileName: "a.txt", mimeType: "text/plain", sizeBytes: 1 },
            { status: 201 },
          );
        }),
      );
      await uploadAttachment("conv1", new File(["x"], "a.txt", { type: "text/plain" }));
      expect(auth).toBe("Bearer tok-123");
    });

    it("sends the token on list/download/delete when configured", async () => {
      api.setAuthToken("tok-xyz");
      const seen: Record<string, string | null> = {};
      server.use(
        http.get("*/conversations/:c/attachments", ({ request }) => {
          seen.list = request.headers.get("Authorization");
          return HttpResponse.json([]);
        }),
        http.get("*/conversations/:c/attachments/:ref", ({ request }) => {
          seen.download = request.headers.get("Authorization");
          return new HttpResponse(new Uint8Array([1]), { status: 200 });
        }),
        http.delete("*/conversations/:c/attachments/:ref", ({ request }) => {
          seen.delete = request.headers.get("Authorization");
          return HttpResponse.json({ deleted: true });
        }),
      );
      await listAttachments("conv1");
      await downloadAttachment("conv1", "ref-1");
      await deleteAttachment("conv1", "ref-1");
      expect(seen).toEqual({ list: "Bearer tok-xyz", download: "Bearer tok-xyz", delete: "Bearer tok-xyz" });
    });

    it("omits the Authorization header entirely when auth is not configured", async () => {
      api.clearAuthToken();
      let hadAuthOnUpload = true;
      let hadAuthOnList = true;
      server.use(
        http.post("*/conversations/:c/attachments", ({ request }) => {
          hadAuthOnUpload = request.headers.has("Authorization");
          return HttpResponse.json(
            { storageRef: "r", fileName: "a.txt", mimeType: "text/plain", sizeBytes: 1 },
            { status: 201 },
          );
        }),
        http.get("*/conversations/:c/attachments", ({ request }) => {
          hadAuthOnList = request.headers.has("Authorization");
          return HttpResponse.json([]);
        }),
      );
      await uploadAttachment("conv1", new File(["x"], "a.txt", { type: "text/plain" }));
      await listAttachments("conv1");
      expect(hadAuthOnUpload).toBe(false);
      expect(hadAuthOnList).toBe(false);
    });
  });

  describe("helpers", () => {
    it("isImageMime", () => {
      expect(isImageMime("image/png")).toBe(true);
      expect(isImageMime("application/pdf")).toBe(false);
      expect(isImageMime(undefined)).toBe(false);
    });

    it("formatBytes", () => {
      expect(formatBytes(undefined)).toBe("");
      expect(formatBytes(-5)).toBe("");
      expect(formatBytes(512)).toBe("512 B");
      expect(formatBytes(1024)).toBe("1.0 KB");
      expect(formatBytes(2048)).toBe("2.0 KB");
      expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
      expect(formatBytes(15 * 1024 * 1024)).toBe("15 MB");
      expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe("3.0 GB");
    });
  });
});
