import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { AgentDetailPage } from "@/pages/agent-detail";

/**
 * The agent detail page: the version's compatibility generation beside its
 * number, and — per environment — what deploying it does to conversations on
 * the agent's other deployed versions.
 *
 * Against the default MSW backend, with agent1's latest version at v3: v3
 * carries generation 2, and deploying it moves the 12 conversations on the
 * compatible v2 while the 4 on the legacy v1 stay.
 */
function atVersion3() {
  server.use(http.get("*/agentstore/agents/:id/currentversion", () => HttpResponse.json(3)));
}

const renderDetail = (id = "agent1") =>
  renderPage(`/manage/agentview/${id}`, <AgentDetailPage />, "/manage/agentview/:id");

describe("AgentDetailPage — version following", () => {
  it("shows the version's compatibility generation", async () => {
    atVersion3();
    renderDetail();
    await waitFor(() =>
      expect(screen.getByTestId("compatibility-generation-badge")).toHaveTextContent("Generation 2"),
    );
  });

  it("previews what deploying this version does to conversations on the other deployed versions", async () => {
    atVersion3();
    renderDetail();

    const production = await screen.findByTestId("deployment-impact-production");
    const follow = production.querySelector('[data-testid="impact-row-2"]');
    expect(follow).toHaveAttribute("data-outcome", "FOLLOW");
    expect(follow).toHaveTextContent("12 active conversations on v2 will continue on v3");
    const stay = production.querySelector('[data-testid="impact-row-1"]');
    expect(stay).toHaveAttribute("data-outcome", "STAY");
    expect(stay).toHaveTextContent("4 active conversations on v1 will stay on v1 (predates version following)");
  });

  it("shows no badge and no preview for an agent that predates version following", async () => {
    renderDetail("agent3");
    await screen.findByTestId("env-badges");
    // Let the impact queries settle before asserting their absence.
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId("compatibility-generation-badge")).not.toBeInTheDocument();
    expect(screen.queryByTestId("deployment-impact-production")).not.toBeInTheDocument();
  });
});
