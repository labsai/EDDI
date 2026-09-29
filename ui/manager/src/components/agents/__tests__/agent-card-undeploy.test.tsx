import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";

const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({ toast: { error: toastError, success: vi.fn() } }));

import { AgentCard } from "@/components/agents/agent-card";

const agent = {
  id: "agent-live-1",
  version: 3,
  resource: "eddi://ai.labs.agent/agentstore/agents/agent-live-1?version=3",
  name: "Live Agent",
  description: "",
  lastModifiedOn: Date.now(),
  createdOn: Date.now(),
};

/**
 * Undeploying from the card takes a live agent offline for everyone using it.
 * It fired on the first click, and when the backend refused (409: active
 * conversations) the card toasted a bare "Undeploy failed", discarding the
 * backend's explanation of what blocks it.
 */
describe("AgentCard — undeploy", () => {
  beforeEach(() => {
    toastError.mockReset();
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
  });

  function renderCard() {
    return renderWithProviders(
      <AgentCard agent={agent} onDuplicate={vi.fn()} onDelete={vi.fn()} />,
    );
  }

  it("asks before undeploying and sends nothing when cancelled", async () => {
    const user = userEvent.setup();
    const undeploy = vi.fn();
    server.use(
      http.post("*/administration/:env/undeploy/:agentId", () => {
        undeploy();
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderCard();

    const toggle = await screen.findByText("Undeploy from production");
    await user.click(toggle);

    expect(await screen.findByText("Undeploy agent?")).toBeInTheDocument();
    await user.click(screen.getByTestId("alert-dialog-cancel"));
    expect(undeploy).not.toHaveBeenCalled();
  });

  it("undeploys on confirm, ending conversations only when asked to", async () => {
    const user = userEvent.setup();
    let query: URLSearchParams | null = null;
    server.use(
      http.post("*/administration/:env/undeploy/:agentId", ({ request }) => {
        query = new URL(request.url).searchParams;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderCard();

    await user.click(await screen.findByText("Undeploy from production"));
    await user.click(screen.getByTestId("agent-undeploy-end-conversations-agent-live-1"));
    await user.click(screen.getByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(query).not.toBeNull());
    expect(query!.get("version")).toBe("3");
    expect(query!.get("endAllActiveConversations")).toBe("true");
  });

  it("shows the backend's reason when the undeploy is refused", async () => {
    const user = userEvent.setup();
    const reason = "Agent has 2 active conversations. End them first or pass endAllActiveConversations=true.";
    server.use(
      http.post("*/administration/:env/undeploy/:agentId", () =>
        new HttpResponse(reason, { status: 409, headers: { "Content-Type": "text/plain" } }),
      ),
    );
    renderCard();

    await user.click(await screen.findByText("Undeploy from production"));
    await user.click(screen.getByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(toastError.mock.calls[0]![0]).toContain("2 active conversations");
  });
});
