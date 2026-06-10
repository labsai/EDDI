import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getWorkflowDescriptors,
  getWorkflow,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  getWorkflowVersions,
  duplicateWorkflow,
} from "../workflows";

describe("workflows API", () => {
  // ─── getWorkflowDescriptors ─────────────────────────────────────
  describe("getWorkflowDescriptors", () => {
    it("fetches workflow descriptors with default params", async () => {
      const result = await getWorkflowDescriptors();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBeGreaterThan(0);
      expect(result[0]).toHaveProperty("resource");
      expect(result[0]).toHaveProperty("name");
    });

    it("passes custom limit and index", async () => {
      const result = await getWorkflowDescriptors(50, 2);
      expect(result).toBeDefined();
    });

    it("passes filter parameter when provided", async () => {
      const result = await getWorkflowDescriptors(100, 0, "Support");
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/workflowstore/workflows/descriptors", () =>
          HttpResponse.json({ message: "Server Error" }, { status: 500 })
        )
      );
      await expect(getWorkflowDescriptors()).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  // ─── getWorkflow ────────────────────────────────────────────────
  describe("getWorkflow", () => {
    it("fetches a workflow by id and version", async () => {
      const result = await getWorkflow("wf1", 2);
      expect(result).toBeDefined();
      expect(result.workflowSteps).toBeDefined();
      expect(Array.isArray(result.workflowSteps)).toBe(true);
    });

    it("handles missing workflow", async () => {
      server.use(
        http.get("*/workflowstore/workflows/:id", () =>
          HttpResponse.json({ message: "Not Found" }, { status: 404 })
        )
      );
      await expect(getWorkflow("nonexistent", 1)).rejects.toMatchObject({
        status: 404,
      });
    });
  });

  // ─── createWorkflow ─────────────────────────────────────────────
  describe("createWorkflow", () => {
    it("creates a workflow and returns location", async () => {
      const result = await createWorkflow({ workflowSteps: [] });
      expect(result).toBeDefined();
      expect(result.location).toBeDefined();
      expect(result.location).toContain("workflowstore/workflows");
    });
  });

  // ─── updateWorkflow ─────────────────────────────────────────────
  describe("updateWorkflow", () => {
    it("updates a workflow and returns location with incremented version", async () => {
      const result = await updateWorkflow("wf1", 2, { workflowSteps: [] });
      expect(result).toBeDefined();
      expect(result.location).toContain("version=3");
    });
  });

  // ─── deleteWorkflow ─────────────────────────────────────────────
  describe("deleteWorkflow", () => {
    it("deletes a workflow", async () => {
      await expect(deleteWorkflow("wf1", 1)).resolves.toBeUndefined();
    });

    it("passes cascade option", async () => {
      await expect(
        deleteWorkflow("wf1", 1, { cascade: true })
      ).resolves.toBeUndefined();
    });

    it("passes permanent option", async () => {
      await expect(
        deleteWorkflow("wf1", 1, { permanent: true })
      ).resolves.toBeUndefined();
    });

    it("passes both cascade and permanent options", async () => {
      await expect(
        deleteWorkflow("wf1", 1, { cascade: true, permanent: true })
      ).resolves.toBeUndefined();
    });
  });

  // ─── getWorkflowVersions ───────────────────────────────────────
  describe("getWorkflowVersions", () => {
    it("fetches versions for a workflow", async () => {
      const result = await getWorkflowVersions("wf1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("falls back to descriptor endpoint when per-version fetch returns empty", async () => {
      // Override currentversion to return 2, but each per-version fetch returns []
      server.use(
        http.get("*/workflowstore/workflows/:id/currentversion", () =>
          HttpResponse.json(2)
        ),
        http.get("*/workflowstore/workflows/descriptors", () =>
          HttpResponse.json([])
        )
      );
      const result = await getWorkflowVersions("wf-empty");
      // When all per-version fetches return empty AND the fallback returns empty
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("handles currentversion returning null (defaults to 1)", async () => {
      server.use(
        http.get("*/workflowstore/workflows/:id/currentversion", () =>
          HttpResponse.json(null)
        )
      );
      const result = await getWorkflowVersions("wf-null-version");
      expect(result).toBeDefined();
    });

    it("handles per-version descriptor fetch error gracefully", async () => {
      server.use(
        http.get("*/workflowstore/workflows/:id/currentversion", () =>
          HttpResponse.json(2)
        ),
        http.get("*/workflowstore/workflows/descriptors", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      // Per-version errors are caught and return [], then fallback is used
      // Fallback also fails, so it throws
      await expect(getWorkflowVersions("wf-error")).rejects.toBeDefined();
    });
  });

  // ─── duplicateWorkflow ──────────────────────────────────────────
  describe("duplicateWorkflow", () => {
    it("duplicates a workflow with shallow copy", async () => {
      server.use(
        http.post("*/workflowstore/workflows/:id", () =>
          new HttpResponse(null, {
            status: 201,
            headers: {
              Location: "eddi://ai.labs.workflow/workflowstore/workflows/dup-wf1?version=1",
            },
          })
        )
      );
      const result = await duplicateWorkflow("wf1", 2);
      expect(result).toBeDefined();
      expect(result.location).toBeDefined();
    });

    it("duplicates a workflow with deep copy", async () => {
      server.use(
        http.post("*/workflowstore/workflows/:id", () =>
          new HttpResponse(null, {
            status: 201,
            headers: {
              Location: "eddi://ai.labs.workflow/workflowstore/workflows/dup-wf1-deep?version=1",
            },
          })
        )
      );
      const result = await duplicateWorkflow("wf1", 2, true);
      expect(result).toBeDefined();
      expect(result.location).toBeDefined();
    });
  });
});
