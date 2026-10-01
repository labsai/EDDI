import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage, renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { MemoryInspector } from "@/components/debugger/memory-inspector";

/**
 * Version following: a conversation may move to a compatible newer version of
 * its agent between turns. The step it moved on carries `agent:switch`, every
 * step records `agent:version`, and a conversation ended because its version
 * was undeployed carries `endReason: "agent-version-retired"`.
 */

const step = (input: string, extra: { key: string; value: unknown }[]) => ({
  conversationStep: [{ key: "input:initial", value: input }, ...extra],
});

function stubSnapshot(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get("*/conversationstore/conversations/simple/:id", () =>
      HttpResponse.json({
        agentId: "agent1",
        agentVersion: 6,
        conversationId: "conv1",
        conversationState: "READY",
        environment: "production",
        conversationSteps: [
          step("first", [{ key: "agent:version", value: 5 }]),
          step("second", [
            { key: "agent:version", value: 6 },
            { key: "agent:switch", value: { from: 5, to: 6 } },
          ]),
        ],
        conversationOutputs: [{}, {}],
        ...overrides,
      }),
    ),
  );
}

const renderDetail = () =>
  renderPage("/manage/conversationview/conv1", <ConversationDetailPage />, "/manage/conversationview/:id");

describe("ConversationDetailPage — version following", () => {
  it("marks the step on which the agent moved to another version", async () => {
    stubSnapshot();
    renderDetail();

    const notices = await screen.findAllByTestId("agent-switch-notice");
    expect(notices).toHaveLength(1);
    expect(notices[0]).toHaveTextContent("Agent moved from v5 to v6");
  });

  it("shows the agent version that ran each step", async () => {
    stubSnapshot();
    renderDetail();

    expect(await screen.findByTestId("step-agent-version-1")).toHaveTextContent("v5");
    expect(screen.getByTestId("step-agent-version-2")).toHaveTextContent("v6");
  });

  it("shows nothing for steps recorded before version following", async () => {
    stubSnapshot({ conversationSteps: [step("old", [])], conversationOutputs: [{}] });
    renderDetail();

    await screen.findByText("old");
    expect(screen.queryByTestId("agent-switch-notice")).not.toBeInTheDocument();
    expect(screen.queryByTestId("step-agent-version-1")).not.toBeInTheDocument();
  });

  it("says why a conversation ended when its agent version was retired", async () => {
    stubSnapshot({ conversationState: "ENDED", endReason: "agent-version-retired" });
    renderDetail();

    expect(await screen.findByTestId("end-reason-retired")).toHaveTextContent(
      "Ended: the agent version was retired",
    );
  });

  it("shows no retirement notice for an ordinary ended conversation", async () => {
    stubSnapshot({ conversationState: "ENDED" });
    renderDetail();

    await screen.findByText("first");
    expect(screen.queryByTestId("end-reason-retired")).not.toBeInTheDocument();
  });
});

describe("MemoryInspector — version following", () => {
  it("shows the switch marker and the step's agent version on the step that moved", async () => {
    server.use(
      http.get("*/agents/:id", () =>
        HttpResponse.json({
          conversationSteps: [
            { conversationStep: [{ key: "agent:version", value: 5, timestamp: null, originWorkflowId: null }], timestamp: null },
            {
              conversationStep: [
                { key: "agent:version", value: 6, timestamp: null, originWorkflowId: null },
                { key: "agent:switch", value: { from: 5, to: 6 }, timestamp: null, originWorkflowId: null },
              ],
              timestamp: null,
            },
          ],
          conversationProperties: {},
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MemoryInspector conversationId="conv-vf" />);

    // Step 1: no move.
    expect(await screen.findByTestId("memory-step-agent-version")).toHaveTextContent("v5");
    expect(screen.queryByTestId("agent-switch-notice")).not.toBeInTheDocument();

    // Step 2: the move is marked.
    const inspector = screen.getByTestId("memory-inspector");
    await user.click(within(inspector).getByRole("button", { name: /Step 2/ }));
    await waitFor(() =>
      expect(screen.getByTestId("agent-switch-notice")).toHaveTextContent("Agent moved from v5 to v6"),
    );
    expect(screen.getByTestId("memory-step-agent-version")).toHaveTextContent("v6");
  });
});
