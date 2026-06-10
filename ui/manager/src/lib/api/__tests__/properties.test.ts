import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  readProperties,
  mergeProperties,
  deleteProperties,
} from "../properties";

describe("properties API", () => {
  describe("readProperties", () => {
    it("reads properties for a user", async () => {
      const result = await readProperties("user1");
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/propertiesstore/properties/:userId", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(readProperties("user-fail")).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  describe("mergeProperties", () => {
    it("merges properties for a user", async () => {
      await expect(
        mergeProperties("user1", {
          lang: {
            name: "lang",
            scope: "conversation",
            valueString: "en",
          },
        })
      ).resolves.toBeUndefined();
    });
  });

  describe("deleteProperties", () => {
    it("deletes properties for a user", async () => {
      await expect(deleteProperties("user1")).resolves.toBeUndefined();
    });
  });
});
