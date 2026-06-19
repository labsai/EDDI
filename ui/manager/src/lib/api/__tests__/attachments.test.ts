import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { uploadAttachment } from "../attachments";

describe("attachments API", () => {
  describe("uploadAttachment", () => {
    it("uploads a file attachment", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () =>
          HttpResponse.json({
            storageRef: "ref-123",
            fileName: "document.pdf",
            mimeType: "application/pdf",
            sizeBytes: 1024,
          })
        )
      );
      const file = new File(["test content"], "document.pdf", {
        type: "application/pdf",
      });
      const result = await uploadAttachment("conv1", file);
      expect(result).toBeDefined();
      expect(result.storageRef).toBe("ref-123");
      expect(result.fileName).toBe("document.pdf");
    });

    it("handles upload failure", async () => {
      server.use(
        http.post("*/conversations/:conversationId/attachments", () =>
          new HttpResponse(null, { status: 413, statusText: "Payload Too Large" })
        )
      );
      const file = new File(["x".repeat(1000)], "big.pdf", {
        type: "application/pdf",
      });
      await expect(uploadAttachment("conv1", file)).rejects.toThrow(
        "Attachment upload failed"
      );
    });
  });
});
