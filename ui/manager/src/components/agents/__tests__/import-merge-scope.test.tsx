import { describe, it, expect, afterEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { api, SPACE_HEADER } from "@/lib/api-client";
import { importSpaceHeader, previewImport } from "@/lib/api/backup";
import { MergeScopeNotice } from "@/components/agents/import-steps/preview-step";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

afterEach(() => {
  api.setCreateSpace(null);
  server.resetHandlers();
  vi.restoreAllMocks();
});

describe("import space header (EDDI 6.6 merge scope)", () => {
  it("is empty with no space selected", () => {
    api.setCreateSpace(null);
    expect(importSpaceHeader()).toEqual({});
  });

  it("carries the selected space, as every other create does", () => {
    api.setCreateSpace("team:ops");
    expect(importSpaceHeader()).toEqual({ [SPACE_HEADER]: "team:ops" });
  });

  it("is sent on the merge preview", async () => {
    let seen: string | null = "unset";
    server.use(
      http.post("*/backup/import/preview", ({ request }) => {
        seen = request.headers.get(SPACE_HEADER);
        return HttpResponse.json({ resources: [] });
      }),
    );
    api.setCreateSpace("team:ops");
    await previewImport(new File(["zip"], "a.zip"));
    expect(seen).toBe("team:ops");
  });

  it("surfaces the server's 400 reason for a malformed archive", async () => {
    server.use(
      http.post("*/backup/import/preview", () =>
        HttpResponse.json({ error: "Archive entry '../../x' would be extracted outside the import directory" }, { status: 400 }),
      ),
    );
    await expect(previewImport(new File(["zip"], "a.zip"))).rejects.toThrow(/outside the import directory/);
  });
});

describe("MergeScopeNotice", () => {
  it("says a merge only updates resources in the importing space when workspaces are enforced", async () => {
    server.use(
      http.get("*/workspaces", () =>
        HttpResponse.json({
          enabled: true,
          principal: "alice",
          seesEverything: false,
          spaces: [{ id: "user:alice", kind: "personal", label: "alice" }],
        }),
      ),
    );
    renderWithProviders(<MergeScopeNotice />);
    expect(await screen.findByTestId("merge-scope-notice")).toHaveTextContent(/never changed/);
  });

  it("stays silent when workspaces are off — nothing about merging changed there", async () => {
    server.use(http.get("*/workspaces", () => HttpResponse.json({ enabled: false, spaces: [] })));
    renderWithProviders(<MergeScopeNotice />);
    await waitFor(() => expect(screen.queryByTestId("merge-scope-notice")).not.toBeInTheDocument());
  });
});
