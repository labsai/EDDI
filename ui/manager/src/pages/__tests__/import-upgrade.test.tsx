import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { ImportAgentDialog } from "@/components/agents/import-agent-dialog";

function renderDialog() {
  return renderWithProviders(
    <ImportAgentDialog open onClose={() => {}} onSuccess={() => {}} />
  );
}

describe("ImportAgentDialog — Upgrade Strategy", () => {
  it("renders drop zone initially", () => {
    renderDialog();
    expect(screen.getByTestId("import-drop-zone")).toBeInTheDocument();
  });

  it("shows 4 strategy options after file upload", async () => {
    renderDialog();
    const user = userEvent.setup();

    // Simulate file selection via the hidden input
    const fileInput = screen.getByTestId("import-file-input");
    const file = new File(["fake-zip"], "agent.zip", { type: "application/zip" });
    await user.upload(fileInput, file);

    // Now we should see the strategy step
    expect(screen.getByTestId("strategy-create")).toBeInTheDocument();
    expect(screen.getByTestId("strategy-merge")).toBeInTheDocument();
    expect(screen.getByTestId("strategy-upgrade")).toBeInTheDocument();
    expect(screen.getByTestId("strategy-sync")).toBeInTheDocument();
  });

  it("upgrade strategy shows target agent picker after clicking Next", async () => {
    renderDialog();
    const user = userEvent.setup();

    // Upload file
    const fileInput = screen.getByTestId("import-file-input");
    const file = new File(["fake-zip"], "agent.zip", { type: "application/zip" });
    await user.upload(fileInput, file);

    // Select upgrade strategy
    await user.click(screen.getByTestId("strategy-upgrade"));

    // Click Next
    await user.click(screen.getByTestId("import-confirm-strategy"));

    // Target picker should appear
    await waitFor(() => {
      expect(screen.getByTestId("upgrade-target-select")).toBeInTheDocument();
    });
  });

  it("sync strategy shows connection panel after clicking Next", async () => {
    renderDialog();
    const user = userEvent.setup();

    // Upload file
    const fileInput = screen.getByTestId("import-file-input");
    const file = new File(["fake-zip"], "agent.zip", { type: "application/zip" });
    await user.upload(fileInput, file);

    // Select sync strategy
    await user.click(screen.getByTestId("strategy-sync"));

    // Click Next
    await user.click(screen.getByTestId("import-confirm-strategy"));

    // Sync config panel should appear
    await waitFor(() => {
      expect(screen.getByTestId("sync-config-panel")).toBeInTheDocument();
    });
  });

  it("back button returns to strategy step from target step", async () => {
    renderDialog();
    const user = userEvent.setup();

    // Upload file → select upgrade → next → target step
    const fileInput = screen.getByTestId("import-file-input");
    const file = new File(["fake-zip"], "agent.zip", { type: "application/zip" });
    await user.upload(fileInput, file);
    await user.click(screen.getByTestId("strategy-upgrade"));
    await user.click(screen.getByTestId("import-confirm-strategy"));

    await waitFor(() => {
      expect(screen.getByTestId("upgrade-target-select")).toBeInTheDocument();
    });

    // Click back
    const backBtn = screen.getByText("Back");
    await user.click(backBtn);

    // Should be back at strategy
    expect(screen.getByTestId("strategy-upgrade")).toBeInTheDocument();
  });

  it("dialog closes when not open", () => {
    renderWithProviders(
      <ImportAgentDialog open={false} onClose={() => {}} onSuccess={() => {}} />
    );
    expect(screen.queryByTestId("import-agent-dialog")).not.toBeInTheDocument();
  });
});

describe("ImportAgentDialog — changes made on this instance", () => {
  it("leaves a CONFLICT unticked, and says why", async () => {
    // A conflict is a resource changed here since the last sync. Ticking every
    // row by default made "Upgrade Now" overwrite that change without the
    // operator ever choosing to.
    server.use(
      http.post("*/backup/import/preview", () =>
        HttpResponse.json({
          sourceAgentId: "agent1",
          sourceAgentName: "Support Agent",
          targetAgentId: "agent1",
          targetAgentName: "Support Agent",
          resources: [
            { sourceId: "out1", resourceType: "output", name: "Outputs", action: "UPDATE", targetId: "o",
              targetVersion: 1, matchStrategy: "type", sourceContent: "{}", targetContent: "{}", workflowIndex: -1 },
            { sourceId: "llm1", resourceType: "langchain", name: "Hotfixed LLM", action: "CONFLICT", targetId: "l",
              targetVersion: 4, matchStrategy: "type", sourceContent: "{}", targetContent: "{}", workflowIndex: -1 },
          ],
        })
      )
    );

    renderDialog();
    const user = userEvent.setup();
    await user.upload(screen.getByTestId("import-file-input"),
      new File(["fake-zip"], "agent.zip", { type: "application/zip" }));
    await user.click(screen.getByTestId("strategy-upgrade"));
    await user.click(screen.getByTestId("import-confirm-strategy"));
    const select = await screen.findByTestId("upgrade-target-select");
    const firstAgent = [...(select as HTMLSelectElement).options].find((o) => o.value)!;
    await user.selectOptions(select, firstAgent.value);
    await user.click(screen.getByTestId("import-target-next"));

    const conflictRow = (await screen.findByText("Hotfixed LLM")).closest("tr")!;
    const updateRow = screen.getByText("Outputs").closest("tr")!;
    expect(conflictRow.querySelector("input[type=checkbox]")).not.toBeChecked();
    expect(updateRow.querySelector("input[type=checkbox]")).toBeChecked();
    expect(screen.getByTestId("preview-notices")).toHaveTextContent(/changed on this instance/);
  });
});

describe("ImportAgentDialog — syncing onto an earlier promotion", () => {
  async function toSyncTarget() {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/prod-copy?version=4",
            name: "Support Agent (prod)",
            description: "",
            createdOn: 1,
            lastModifiedOn: 1,
            originId: "remote-agent1",
          },
        ])
      )
    );
    renderDialog();
    const user = userEvent.setup();
    await user.upload(screen.getByTestId("import-file-input"),
      new File(["fake-zip"], "agent.zip", { type: "application/zip" }));
    await user.click(screen.getByTestId("strategy-sync"));
    await user.click(screen.getByTestId("import-confirm-strategy"));
    await user.type(await screen.findByTestId("sync-url-input"), "https://staging.eddi.example.com");
    await user.click(screen.getByTestId("sync-connect-btn"));
    await user.selectOptions(await screen.findByTestId("sync-source-select"), "remote-agent1");
    return user;
  }

  it("pre-selects the local copy promoted from the chosen agent", async () => {
    // What a sync with no target does anyway — shown before the preview, not after.
    await toSyncTarget();

    await waitFor(() =>
      expect((screen.getByTestId("sync-target-select") as HTMLSelectElement).value).toBe("prod-copy")
    );
  });

  it("creates a new agent only when the operator picks Create new", async () => {
    const previews: URLSearchParams[] = [];
    server.use(
      http.post("*/backup/import/sync/preview", ({ request }) => {
        previews.push(new URL(request.url).searchParams);
        return HttpResponse.json({
          sourceAgentId: "remote-agent1", sourceAgentName: "Support Agent",
          targetAgentId: null, targetAgentName: null, resources: [],
        });
      })
    );
    const user = await toSyncTarget();

    await user.selectOptions(screen.getByTestId("sync-target-select"), "");
    await user.click(screen.getByTestId("import-target-next"));

    await waitFor(() => expect(previews).toHaveLength(1));
    expect(previews[0]!.get("createNew")).toBe("true");
    expect(previews[0]!.get("targetAgentId")).toBeNull();
  });
});
