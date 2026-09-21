import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
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
