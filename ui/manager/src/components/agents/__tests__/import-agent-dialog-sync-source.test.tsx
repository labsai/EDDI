import { describe, it, expect, vi } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse, type JsonBodyType } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ImportAgentDialog } from "@/components/agents/import-agent-dialog";

// The sibling import-agent-dialog.test.tsx mocks the whole backup hook module.
// These run against the real hooks and the MSW backup/sync handlers instead,
// because what is under test is state that outlives a request.
//
// The remote agent list, the source/target picked from it and any preview built
// on them belong to the instance they were fetched from. Editing the URL or the
// token used to keep all of it, so instance A's agents could be previewed and
// synced against instance B.

type User = ReturnType<typeof userEvent.setup>;

/**
 * An MSW resolver that holds its reply until released. `started` resolves once
 * the request has reached the handler and `served` once the reply has left it,
 * so a test never releases a request that is not there yet.
 */
function heldReply(body: JsonBodyType) {
  let release!: () => void;
  let markStarted!: () => void;
  let markServed!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  const started = new Promise<void>((r) => (markStarted = r));
  const served = new Promise<void>((r) => (markServed = r));
  const resolver = async () => {
    markStarted();
    await gate;
    markServed();
    return HttpResponse.json(body);
  };
  return { resolver, started, served, release: () => release() };
}

/** Release a held reply and let the client apply whatever it is going to. */
async function releaseAndSettle(held: ReturnType<typeof heldReply>) {
  await act(async () => {
    held.release();
    await held.served;
    await new Promise((r) => setTimeout(r, 50));
  });
}

function renderDialog() {
  return renderWithProviders(
    <ImportAgentDialog open onClose={vi.fn()} onSuccess={vi.fn()} />
  );
}

/** Upload, choose "sync", connect, pick a remote source and a local target. */
async function connectAndPick(user: User) {
  await user.upload(
    screen.getByTestId("import-file-input"),
    new File(["zip"], "agent.zip", { type: "application/zip" })
  );
  await user.click(screen.getByTestId("strategy-sync"));
  await user.click(screen.getByTestId("import-confirm-strategy"));

  await user.type(screen.getByTestId("sync-url-input"), "https://a.example.com");
  await user.type(screen.getByTestId("sync-auth-input"), "Bearer a");
  await user.click(screen.getByTestId("sync-connect-btn"));

  const source = (await screen.findByTestId("sync-source-select")) as HTMLSelectElement;
  await user.selectOptions(source, "remote-agent1");

  const target = screen.getByTestId("sync-target-select") as HTMLSelectElement;
  await waitFor(() => expect(target.options.length).toBeGreaterThan(1));
  await user.selectOptions(target, target.options[1]!.value);

  expect(source.value).toBe("remote-agent1");
  expect(target.value).not.toBe("");
  expect(screen.getByTestId("import-target-next")).toBeEnabled();
}

/** Preview, then come back to the target step with that preview in state. */
async function previewAndReturn(user: User) {
  await user.click(screen.getByTestId("import-target-next"));
  expect(await screen.findByText("Support Rules")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /back/i }));
  expect(await screen.findByTestId("sync-source-select")).toBeInTheDocument();
}

/** The old instance's list, selection and preview are all gone. */
async function expectSourceDropped(user: User) {
  expect(screen.queryByTestId("sync-source-select")).not.toBeInTheDocument();
  expect(screen.queryByTestId("sync-target-select")).not.toBeInTheDocument();
  expect(screen.getByTestId("import-target-next")).toBeDisabled();

  // Reconnecting brings a list back, but not the selection made against the
  // old source: hiding the list while keeping the selection would leave
  // "Preview Changes" armed for an agent nobody picked on this instance.
  await user.click(screen.getByTestId("sync-connect-btn"));
  const source = (await screen.findByTestId("sync-source-select")) as HTMLSelectElement;
  expect(source.value).toBe("");
  expect((screen.getByTestId("sync-target-select") as HTMLSelectElement).value).toBe("");
  expect(screen.getByTestId("import-target-next")).toBeDisabled();
}

describe("ImportAgentDialog — sync source changes", () => {
  it("drops the remote agents, selection and preview when the source URL is edited", async () => {
    renderDialog();
    const user = userEvent.setup();
    await connectAndPick(user);
    await previewAndReturn(user);

    await user.type(screen.getByTestId("sync-url-input"), "/b");

    await expectSourceDropped(user);
  });

  it("drops the remote agents, selection and preview when the token is edited", async () => {
    renderDialog();
    const user = userEvent.setup();
    await connectAndPick(user);
    await previewAndReturn(user);

    await user.type(screen.getByTestId("sync-auth-input"), "x");

    await expectSourceDropped(user);
  });

  it("a preview still in flight when the URL is edited does not land", async () => {
    const held = heldReply({
      sourceAgentId: "remote-agent1",
      sourceAgentName: "Stale Preview",
      targetAgentId: null,
      targetAgentName: null,
      resources: [],
    });
    server.use(http.post("*/backup/import/sync/preview", held.resolver));

    renderDialog();
    const user = userEvent.setup();
    await connectAndPick(user);
    await user.click(screen.getByTestId("import-target-next"));
    await held.started;

    await user.type(screen.getByTestId("sync-url-input"), "/b");
    await releaseAndSettle(held);

    expect(screen.queryByText("Stale Preview")).not.toBeInTheDocument();
    expect(screen.getByTestId("sync-url-input")).toBeInTheDocument();
    expect(screen.getByTestId("import-target-next")).toBeDisabled();
  });

  // The connect request is sent by SyncConfigPanel. A reply for the URL or
  // token it was sent with used to land after an edit and fill the list the
  // dialog had just cleared with the old instance's agents.
  it.each([
    ["URL", "sync-url-input", "/b"],
    ["token", "sync-auth-input", "x"],
  ])("a connect reply still in flight when the %s is edited does not land", async (_, testId, typed) => {
    const held = heldReply([
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/stale-agent?version=1",
        name: "Stale Agent",
        description: "",
        lastModifiedOn: new Date().toISOString(),
      },
    ]);
    server.use(http.get("*/backup/import/sync/agents", held.resolver));

    renderDialog();
    const user = userEvent.setup();
    await user.upload(
      screen.getByTestId("import-file-input"),
      new File(["zip"], "agent.zip", { type: "application/zip" })
    );
    await user.click(screen.getByTestId("strategy-sync"));
    await user.click(screen.getByTestId("import-confirm-strategy"));
    await user.type(screen.getByTestId("sync-url-input"), "https://a.example.com");
    await user.click(screen.getByTestId("sync-connect-btn"));
    await held.started;

    await user.type(screen.getByTestId(testId), typed);
    await releaseAndSettle(held);

    expect(screen.queryByText("Stale Agent")).not.toBeInTheDocument();
    expect(screen.queryByTestId("sync-source-select")).not.toBeInTheDocument();
    expect(screen.getByTestId("import-target-next")).toBeDisabled();
  });
});
