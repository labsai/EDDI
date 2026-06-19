import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { previewTemplate } from "../template-preview";

describe("template-preview API", () => {
  describe("previewTemplate", () => {
    it("previews a template with variable resolution", async () => {
      const result = await previewTemplate({
        template: "Hello {properties.userName}!",
      });
      expect(result).toBeDefined();
      expect(result.resolved).toContain("Hello");
      expect(result.availableVariables).toBeDefined();
      expect(result.variableValues).toBeDefined();
      expect(result.error).toBeNull();
    });

    it("previews with conversationId", async () => {
      const result = await previewTemplate({
        template: "Conv: {conversationInfo.conversationId}",
        conversationId: "conv-123",
      });
      expect(result).toBeDefined();
      expect(result.resolved).toContain("conv-123");
    });

    it("handles API error", async () => {
      server.use(
        http.post("*/administration/preview/template", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(
        previewTemplate({ template: "test" })
      ).rejects.toMatchObject({ status: 500 });
    });
  });
});
