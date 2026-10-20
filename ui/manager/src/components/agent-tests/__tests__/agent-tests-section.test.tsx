import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AgentTestsSection } from "@/components/agent-tests/agent-tests-section";
import { server } from "@/test/mocks/server";

async function open() {
  renderWithProviders(<AgentTestsSection agentId="agent1" agentVersion={7} />);
  await userEvent.click(screen.getByRole("button", { name: /golden conversations/i }));
}

describe("AgentTestsSection", () => {
  it("lists the run history, newest first", async () => {
    await open();
    await waitFor(() => expect(screen.getAllByTestId("agent-test-run-row")).toHaveLength(5));
    const rows = screen.getAllByTestId("agent-test-run-row");
    expect(within(rows[0]!).getByTestId("run-status-FAILED")).toBeInTheDocument();
    expect(within(rows[2]!).getByTestId("run-status-ABORTED")).toBeInTheDocument();
  });

  it("starts a run for the selected suite and agent version and opens it", async () => {
    let body: unknown;
    server.use(
      http.post("*/agenttests/runs", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ id: "run-x", status: "RUNNING", agentId: "agent1", suiteId: "res1" }, { status: 202 });
      })
    );
    await open();
    await waitFor(() => expect(screen.getByTestId("agent-test-run-btn")).toBeEnabled());
    await userEvent.click(screen.getByTestId("agent-test-run-btn"));
    await waitFor(() => expect(body).toEqual({ suiteId: "res1", suiteVersion: 1, agentId: "agent1", agentVersion: 7 }));
    expect(await screen.findByTestId("run-result-sheet")).toBeInTheDocument();
  });

  it("does not open a sheet when the backend refuses the run", async () => {
    server.use(
      http.post("*/agenttests/runs", () => HttpResponse.json({ error: "A newer version is deployed to test" }, { status: 409 }))
    );
    await open();
    await waitFor(() => expect(screen.getByTestId("agent-test-run-btn")).toBeEnabled());
    await userEvent.click(screen.getByTestId("agent-test-run-btn"));
    await waitFor(() => expect(screen.getByTestId("agent-test-run-btn")).toBeEnabled());
    expect(screen.queryByTestId("run-result-sheet")).not.toBeInTheDocument();
  });

  it("opens a run from the history into the side sheet", async () => {
    await open();
    const rows = await screen.findAllByTestId("agent-test-run-row");
    await userEvent.click(rows[2]!);
    expect(await screen.findByTestId("run-notice-aborted")).toBeInTheDocument();
  });

  it("says so when there are no suites", async () => {
    server.use(http.get("*/testsuitestore/testsuites/descriptors", () => HttpResponse.json([])));
    await open();
    expect(await screen.findByTestId("agent-tests-no-suites")).toBeInTheDocument();
    expect(screen.getByTestId("agent-test-run-btn")).toBeDisabled();
  });
});
