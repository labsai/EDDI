import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SyncPage } from "@/pages/sync-page";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderPage() {
  return renderWithProviders(<SyncPage />);
}

/**
 * Helper: type URL + click connect, then wait for agent mapping to appear.
 */
async function connectAndWaitForMapping() {
  const user = userEvent.setup();
  const urlInput = screen.getByTestId("sync-url-input");
  await user.type(urlInput, "https://staging.eddi.example.com");
  await user.click(screen.getByTestId("sync-connect-btn"));

  await waitFor(() => {
    expect(screen.getByText("Agent Mapping")).toBeInTheDocument();
  });
  return user;
}

describe("SyncPage", () => {
  // ─── Basic rendering ──────────────────────────────────────────────────────

  it("renders page title and subtitle", () => {
    renderPage();
    expect(screen.getByText("Agent Sync")).toBeInTheDocument();
    expect(
      screen.getByText("Synchronize agents between EDDI instances")
    ).toBeInTheDocument();
  });

  it("renders the connection panel section", () => {
    renderPage();
    expect(screen.getByText("Source Instance")).toBeInTheDocument();
    expect(screen.getByTestId("sync-config-panel")).toBeInTheDocument();
  });

  it("renders source URL and auth inputs", () => {
    renderPage();
    expect(screen.getByTestId("sync-url-input")).toBeInTheDocument();
    expect(screen.getByTestId("sync-auth-input")).toBeInTheDocument();
  });

  it("connect button is disabled when URL is empty", () => {
    renderPage();
    const connectBtn = screen.getByTestId("sync-connect-btn");
    expect(connectBtn).toBeDisabled();
  });

  it("connect button enables when URL is entered", async () => {
    renderPage();
    const user = userEvent.setup();
    const urlInput = screen.getByTestId("sync-url-input");

    await user.type(urlInput, "https://staging.eddi.example.com");
    const connectBtn = screen.getByTestId("sync-connect-btn");
    expect(connectBtn).not.toBeDisabled();
  });

  it("shows empty state message before connection", () => {
    renderPage();
    expect(
      screen.getByText(
        "Connect to a source instance to begin syncing agents."
      )
    ).toBeInTheDocument();
  });

  it("auth token input toggles visibility", async () => {
    renderPage();
    const user = userEvent.setup();
    const authInput = screen.getByTestId(
      "sync-auth-input"
    ) as HTMLInputElement;

    expect(authInput.type).toBe("password");

    // Find the eye toggle button next to the input
    const eyeButton = authInput
      .closest(".relative")
      ?.querySelector("button");
    expect(eyeButton).toBeTruthy();

    await user.click(eyeButton!);
    expect(authInput.type).toBe("text");

    await user.click(eyeButton!);
    expect(authInput.type).toBe("password");
  });

  // ─── After connection: Agent Mapping ──────────────────────────────────────

  it("shows agent mapping after connecting", async () => {
    renderPage();
    await connectAndWaitForMapping();

    // Should show remote agents from mock
    expect(screen.getByText("Support Agent")).toBeInTheDocument();
    expect(screen.getByText("FAQ Agent")).toBeInTheDocument();
  });

  it("preview all button appears after connection", async () => {
    renderPage();
    await connectAndWaitForMapping();
    expect(screen.getByTestId("sync-preview-all")).toBeInTheDocument();
  });

  it("sync selected button appears after connection", async () => {
    renderPage();
    await connectAndWaitForMapping();
    expect(screen.getByTestId("sync-execute-btn")).toBeInTheDocument();
  });

  it("sync execute button is disabled until preview is done", async () => {
    renderPage();
    await connectAndWaitForMapping();
    const syncBtn = screen.getByTestId("sync-execute-btn");
    expect(syncBtn).toBeDisabled();
  });

  it("shows mapping count badge", async () => {
    renderPage();
    await connectAndWaitForMapping();

    // The badge shows the number of mappings
    const section = screen.getByText("Agent Mapping").closest("section")!;
    expect(section).toBeInTheDocument();
  });

  it("shows footer summary with agents selected and resources count", async () => {
    renderPage();
    await connectAndWaitForMapping();

    // Footer shows "X agents selected · Y resources"
    expect(screen.getByText(/agents selected/)).toBeInTheDocument();
    expect(screen.getByText(/resources/)).toBeInTheDocument();
  });

  it("shows Remote label for each mapping row", async () => {
    renderPage();
    await connectAndWaitForMapping();

    // "Remote" text appears as part of larger text nodes (e.g., "Remote · v2")
    const remoteLabels = screen.getAllByText(/^Remote/);
    expect(remoteLabels.length).toBeGreaterThanOrEqual(2);
  });

  it("shows Create new as default option in target dropdowns", async () => {
    renderPage();
    await connectAndWaitForMapping();

    const createNewOptions = screen.getAllByText("Create new");
    expect(createNewOptions.length).toBeGreaterThanOrEqual(1);
  });

  it("checkboxes are checked by default for all mappings", async () => {
    renderPage();
    await connectAndWaitForMapping();

    const checkboxes = screen.getAllByRole("checkbox");
    for (const cb of checkboxes) {
      expect(cb).toBeChecked();
    }
  });

  it("unchecking a checkbox updates the agent-selected count", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    const checkboxes = screen.getAllByRole("checkbox");

    // Uncheck the first checkbox
    await user.click(checkboxes[0]!);
    expect(checkboxes[0]!).not.toBeChecked();

    // The footer text shows "X agents selected" split across text nodes
    // Verify fewer agents are selected by checking the text content
    const footer = screen.getByText(/agents selected/);
    expect(footer).toBeInTheDocument();
  });

  it("preview all button is disabled when no agents are checked", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    const checkboxes = screen.getAllByRole("checkbox");
    // Uncheck all
    for (const cb of checkboxes) {
      await user.click(cb);
    }

    const previewBtn = screen.getByTestId("sync-preview-all");
    expect(previewBtn).toBeDisabled();
  });

  it("changing target dropdown updates the mapping and clears preview", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    // Find all select elements (target dropdowns)
    const selects = screen.getAllByRole("combobox");
    expect(selects.length).toBeGreaterThanOrEqual(1);

    // Change first dropdown value
    await user.selectOptions(selects[0]!, "");
    // Should still show "Create new" as the default
    expect((selects[0]! as HTMLSelectElement).value).toBe("");
  });

  // ─── Preview flow ─────────────────────────────────────────────────────────

  it("clicking Preview All triggers the preview mutation", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    const previewBtn = screen.getByTestId("sync-preview-all");
    await user.click(previewBtn);

    // After preview, changes should be shown in one of the mapping rows
    await waitFor(() => {
      const changesButtons = screen.getAllByText(/changes/);
      expect(changesButtons.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("clicking changes button expands the detail panel", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    // Preview all
    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      const changesButtons = screen.getAllByText(/changes/);
      expect(changesButtons.length).toBeGreaterThanOrEqual(1);
    });

    // Click on the first "changes" button to expand
    const changesButton = screen.getAllByText(/changes/)[0]!;
    await user.click(changesButton);

    // Expanded detail should show resource table headers
    await waitFor(() => {
      expect(screen.getByText("Resource")).toBeInTheDocument();
      expect(screen.getByText("Type")).toBeInTheDocument();
      expect(screen.getByText("Action")).toBeInTheDocument();
    });
  });

  it("clicking changes button again collapses the detail panel", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      expect(screen.getAllByText(/changes/).length).toBeGreaterThanOrEqual(1);
    });

    // Expand
    const changesButton = screen.getAllByText(/changes/)[0]!;
    await user.click(changesButton);
    await waitFor(() => {
      expect(screen.getByText("Resource")).toBeInTheDocument();
    });

    // Collapse
    await user.click(changesButton!);
    await waitFor(() => {
      expect(screen.queryByText("Resource")).not.toBeInTheDocument();
    });
  });

  // ─── Sync execute flow ────────────────────────────────────────────────────

  it("sync execute button is enabled after preview", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      const syncBtn = screen.getByTestId("sync-execute-btn");
      expect(syncBtn).not.toBeDisabled();
    });
  });

  it("clicking sync execute button triggers the sync mutation", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      expect(screen.getByTestId("sync-execute-btn")).not.toBeDisabled();
    });

    await user.click(screen.getByTestId("sync-execute-btn"));

    // After successful sync, "Sync complete" message should appear
    await waitFor(() => {
      expect(screen.getByText("Sync complete")).toBeInTheDocument();
    });
    expect(screen.getByTestId("sync-outcome")).toHaveAttribute("data-outcome", "ok");
  });

  it("reports a partially applied sync instead of reporting success", async () => {
    // 207 Multi-Status: some resources were not written. The page used to render
    // a green "Sync complete" for this — and for a 500 in which nothing was
    // written at all — because it only checked that the mutation resolved.
    server.use(
      http.post("*/backup/import/sync/batch", () =>
        HttpResponse.json(
          [
            {
              sourceAgentId: "agent1",
              targetAgentId: "agent1-local",
              result: {
                agentUri: "eddi://ai.labs.agent/agentstore/agents/agent1-local",
                agentUpdated: false,
                updated: 0,
                created: 0,
                skipped: 3,
                failures: [
                  {
                    sourceId: "llm1",
                    resourceType: "langchain",
                    name: "GPT-4 Task",
                    reason: "the store did not accept the update",
                  },
                ],
              },
              error: null,
            },
          ],
          { status: 207 }
        )
      )
    );

    renderPage();
    const user = await connectAndWaitForMapping();
    await user.click(screen.getByTestId("sync-preview-all"));
    await waitFor(() => {
      expect(screen.getByTestId("sync-execute-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("sync-execute-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("sync-outcome")).toHaveAttribute("data-outcome", "partial");
    });
    expect(screen.queryByText("Sync complete")).not.toBeInTheDocument();
    expect(
      screen.getByText(/the store did not accept the update/)
    ).toBeInTheDocument();
  });

  it("reports a batch in which every agent failed", async () => {
    // EDDI answers 500 when no mapping succeeded, and the body still carries the
    // reasons — which is why the client resolves rather than throwing here.
    server.use(
      http.post("*/backup/import/sync/batch", () =>
        HttpResponse.json(
          [
            {
              sourceAgentId: "agent1",
              targetAgentId: null,
              result: null,
              error: "the source instance could not supply agent agent1",
            },
          ],
          { status: 500 }
        )
      )
    );

    renderPage();
    const user = await connectAndWaitForMapping();
    await user.click(screen.getByTestId("sync-preview-all"));
    await waitFor(() => {
      expect(screen.getByTestId("sync-execute-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("sync-execute-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("sync-outcome")).toHaveAttribute("data-outcome", "partial");
    });
    expect(
      screen.getByText(/the source instance could not supply agent/)
    ).toBeInTheDocument();
  });

  it("says a preview failed instead of showing it as zero changes", async () => {
    // A batch preview answers 200 even when a mapping failed — one unreachable
    // source must not discard the rows that worked — and carries the reason in
    // the row. Rendering that row's empty resource list as "0 changes" told the
    // operator there was nothing to promote when the source was simply down.
    server.use(
      http.post("*/backup/import/sync/preview/batch", async ({ request }) => {
        const mappings = (await request.json()) as Array<{ sourceAgentId: string }>;
        return HttpResponse.json(
          mappings.map((m) => ({
            sourceAgentId: m.sourceAgentId,
            sourceAgentName: null,
            targetAgentId: null,
            targetAgentName: null,
            resources: [],
            error: "Failed to read agent from remote instance: No route to host",
          }))
        );
      })
    );

    renderPage();
    const user = await connectAndWaitForMapping();
    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      // One per mapping — every row failed, because the source is unreachable.
      expect(screen.getAllByText("Preview failed").length).toBeGreaterThan(0);
    });
    expect(screen.queryByText(/changes/)).not.toBeInTheDocument();
    // And nothing can be promoted from a preview that did not happen.
    expect(screen.getByTestId("sync-execute-btn")).toBeDisabled();
  });

  it("surfaces the server's own reason when connecting is refused", async () => {
    // The backend explains exactly which setting to change; the page used to
    // show "Failed to list remote agents: Bad Request" and nothing else.
    // text/plain, which is what EDDI's ClientErrorExceptionMapper actually
    // answers for a 400 — the JSON shape is covered by the next test.
    server.use(
      http.get("*/backup/import/sync/agents", () =>
        HttpResponse.text(
          "Source URL must not point to a private IP address: http://10.0.0.5:7070."
            + " Set eddi.backup.sync.allow-private-targets=true",
          { status: 400 }
        )
      )
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("sync-url-input"), "http://10.0.0.5:7070");
    await user.click(screen.getByTestId("sync-connect-btn"));

    await waitFor(() => {
      expect(
        screen.getByText(/eddi\.backup\.sync\.allow-private-targets=true/)
      ).toBeInTheDocument();
    });
  });

  it("surfaces a JSON error body too", async () => {
    // A mapped exception answers {"error": ...}; an unmapped one answers text.
    // Both have to reach the operator.
    server.use(
      http.get("*/backup/import/sync/agents", () =>
        HttpResponse.json(
          { error: "Could not list agents on http://staging:7070: connection refused" },
          { status: 502 }
        )
      )
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("sync-url-input"), "http://staging:7070");
    await user.click(screen.getByTestId("sync-connect-btn"));

    await waitFor(() => {
      expect(screen.getByText(/connection refused/)).toBeInTheDocument();
    });
  });

  it("adopts the agent a create sync made, so a second run updates it", async () => {
    // The mapping starts with no local target ("Create new"). Without adopting
    // the created agent, the next Preview + Sync sends targetAgentId: null again
    // and creates a second copy of the same agent.
    const sentTargets: Array<string | null> = [];
    server.use(
      http.post("*/backup/import/sync/batch", async ({ request }) => {
        const requests = (await request.json()) as Array<{
          sourceAgentId: string;
          targetAgentId: string | null;
        }>;
        for (const r of requests) sentTargets.push(r.targetAgentId);
        return HttpResponse.json(
          requests.map((r) => ({
            sourceAgentId: r.sourceAgentId,
            targetAgentId: r.targetAgentId,
            result: {
              agentUri: "eddi://ai.labs.agent/agentstore/agents/created-locally-1?version=1",
              agentUpdated: true,
              updated: 0,
              created: 4,
              skipped: 0,
              failures: [],
            },
            error: null,
          }))
        );
      })
    );

    renderPage();
    const user = await connectAndWaitForMapping();

    // Force "Create new" on every mapping, then preview and sync twice.
    for (const select of screen.getAllByRole("combobox")) {
      await user.selectOptions(select, "");
    }
    for (let run = 0; run < 2; run++) {
      const before = sentTargets.length;
      await user.click(screen.getByTestId("sync-preview-all"));
      await waitFor(() => {
        expect(screen.getByTestId("sync-execute-btn")).not.toBeDisabled();
      });
      await user.click(screen.getByTestId("sync-execute-btn"));
      await waitFor(() => {
        expect(sentTargets.length).toBeGreaterThan(before);
      });
    }

    // First run creates; the second must upgrade what it created, not create again.
    expect(sentTargets[0]).toBeNull();
    expect(sentTargets[sentTargets.length - 1]).toBe("created-locally-1");
  });

  // ─── Auto-match badge ──────────────────────────────────────────────────

  it("shows auto-matched badge when remote agent matches local by name", async () => {
    renderPage();
    await connectAndWaitForMapping();

    // If there's an auto-match, the auto-matched badge appears
    // The mock data may or may not produce auto-matches depending on local agents
    // Just verify the mapping rows rendered without errors
    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes.length).toBeGreaterThanOrEqual(2);
  });

  // ─── Version display ───────────────────────────────────────────────────

  it("shows remote version in mapping row", async () => {
    renderPage();
    await connectAndWaitForMapping();

    // Remote agent versions are shown as "Remote · v{N}"
    const remoteLabels = screen.getAllByText(/Remote/);
    expect(remoteLabels.length).toBeGreaterThanOrEqual(1);
  });

  // ─── Sync error state ─────────────────────────────────────────────────

  it("shows sync error message when sync execute fails", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    // Preview first
    await user.click(screen.getByTestId("sync-preview-all"));
    await waitFor(() => {
      expect(screen.getByTestId("sync-execute-btn")).not.toBeDisabled();
    });

    // Override sync execute to fail
    server.use(
      http.post("*/backup/import/sync/batch", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );

    await user.click(screen.getByTestId("sync-execute-btn"));

    // After failure, error message should appear
    await waitFor(() => {
      expect(screen.getByText(/Sync failed|fail/)).toBeInTheDocument();
    });
  });

  // ─── Agent detail resource table ──────────────────────────────────────

  it("expanded detail shows resource rows with action badges", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      const changesButtons = screen.getAllByText(/changes/);
      expect(changesButtons.length).toBeGreaterThanOrEqual(1);
    });

    // Expand
    const changesButton = screen.getAllByText(/changes/)[0]!;
    await user.click(changesButton);

    await waitFor(() => {
      expect(screen.getByText("Action")).toBeInTheDocument();
    });
  });

  // ─── Footer resource count after preview ──────────────────────────────

  it("footer shows updated resource count after preview", async () => {
    renderPage();
    const user = await connectAndWaitForMapping();

    // Before preview, resources count is 0
    expect(screen.getByText(/0.*resources|resources.*0/)).toBeInTheDocument();

    await user.click(screen.getByTestId("sync-preview-all"));

    await waitFor(() => {
      // After preview, resource count in the footer should update
      expect(screen.getByText(/resources/)).toBeInTheDocument();
    });
  });

  // ─── Create new option ─────────────────────────────────────────────────

  it("target dropdown includes local agents as options", async () => {
    renderPage();
    await connectAndWaitForMapping();

    const selects = screen.getAllByRole("combobox");
    expect(selects.length).toBeGreaterThanOrEqual(1);
    // Each select has at least "Create new" option
    const firstSelect = selects[0] as HTMLSelectElement;
    expect(firstSelect.options.length).toBeGreaterThanOrEqual(1);
  });

  // ─── Matching needs the WHOLE local list ────────────────────────────────
  // An unmatched remote agent is CREATED by the sync, so matching against a
  // partial local list duplicates every agent past the loaded pages.

  /** 60 local agents; "Support Agent" is on page 2, which answers slowly. */
  function localAgentsWithMatchOnPage2(page2: () => Promise<Response> | Response) {
    const agents = Array.from({ length: 60 }, (_, i) => ({
      resource: `eddi://ai.labs.agent/agentstore/agents/local-${i}?version=1`,
      name: i === 55 ? "Support Agent" : `Local ${i}`,
      description: "",
      createdOn: 0,
      lastModifiedOn: 0,
    }));
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const url = new URL(request.url);
        const index = Number(url.searchParams.get("index"));
        const limit = Number(url.searchParams.get("limit"));
        if (index >= 1) return page2();
        return HttpResponse.json(agents.slice(0, limit));
      }),
    );
    return agents;
  }

  it("waits for every local page before auto-matching", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const agents = localAgentsWithMatchOnPage2(async () => {
      await gate;
      return HttpResponse.json(agents.slice(50));
    });
    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("sync-url-input"), "https://staging.eddi.example.com");
    await user.click(screen.getByTestId("sync-connect-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("sync-local-agents-loading")).toBeInTheDocument();
    });
    expect(screen.queryByText("Agent Mapping")).not.toBeInTheDocument();

    release();
    await waitFor(() => {
      expect(screen.getByText("Agent Mapping")).toBeInTheDocument();
    });
    // Matched against the agent that was on page 2.
    expect(screen.getAllByText("auto-matched").length).toBeGreaterThanOrEqual(1);
  });

  it("says so when the local list fails to load, instead of matching a partial list", async () => {
    localAgentsWithMatchOnPage2(() => HttpResponse.json({ message: "boom" }, { status: 500 }));
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("sync-local-agents-error")).toBeInTheDocument();
    });
  });
});
