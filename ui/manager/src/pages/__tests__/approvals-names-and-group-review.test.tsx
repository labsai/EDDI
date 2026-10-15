import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalsPage } from "@/pages/approvals";

/**
 * The inbox is where an approver decides WITHOUT the surrounding page, so each
 * row has to say who it belongs to, and a group verdict has to be reviewable.
 */

const base = {
  pausedAt: "2026-07-01T10:00:00.000Z",
  pauseReason: "Needs sign-off",
  timeoutPolicy: "WAIT_INDEFINITELY",
  pauseType: "RULE",
  userId: "u1",
};

function serve({
  regular = [],
  groups = [],
}: {
  regular?: Record<string, unknown>[];
  groups?: Record<string, unknown>[];
}) {
  server.use(
    http.get("*/agents/pending-approvals", () => HttpResponse.json(regular)),
    http.get("*/groups/pending-approvals", () => HttpResponse.json(groups)),
    http.get("*/agentstore/agents/descriptors", () =>
      HttpResponse.json([
        { resource: "eddi://ai.labs.agent/agentstore/agents/agent-a?version=1", name: "Billing Bot", description: "", createdOn: 1, lastModifiedOn: 1 },
        { resource: "eddi://ai.labs.agent/agentstore/agents/agent-b?version=3", name: "Refund Bot", description: "", createdOn: 1, lastModifiedOn: 1 },
      ]),
    ),
    http.get("*/groupstore/groups/descriptors", () =>
      HttpResponse.json([
        { resource: "eddi://ai.labs.group/groupstore/groups/grp1?version=2", name: "Launch Council", description: "", createdOn: 1, lastModifiedOn: 1 },
      ]),
    ),
  );
}

describe("ApprovalsPage — which agent or group is this?", () => {
  it("leads each row with the agent's name, so two rows with similar ids can be told apart", async () => {
    serve({
      regular: [
        { ...base, conversationId: "conv-awaiting-1", agentId: "agent-a" },
        { ...base, conversationId: "conv-awaiting-2", agentId: "agent-b" },
      ],
    });
    renderWithProviders(<ApprovalsPage />);

    expect(await screen.findByTestId("name-conv-awaiting-1")).toHaveTextContent("Billing Bot");
    expect(screen.getByTestId("name-conv-awaiting-2")).toHaveTextContent("Refund Bot");
    // The column header is translated, not a bare "ID".
    expect(screen.getByRole("columnheader", { name: "Conversation" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "ID" })).not.toBeInTheDocument();
  });

  it("searches by agent and group name", async () => {
    serve({
      regular: [
        { ...base, conversationId: "conv-awaiting-1", agentId: "agent-a" },
        { ...base, conversationId: "conv-awaiting-2", agentId: "agent-b" },
      ],
      groups: [{ ...base, conversationId: "gc-1", groupId: "grp1" }],
    });
    renderWithProviders(<ApprovalsPage />);
    await screen.findByTestId("name-conv-awaiting-1");
    await screen.findByTestId("name-gc-1");

    await userEvent.type(screen.getByTestId("approval-search"), "refund");
    await waitFor(() => {
      expect(screen.queryByTestId("name-conv-awaiting-1")).not.toBeInTheDocument();
    });
    expect(screen.getByTestId("name-conv-awaiting-2")).toBeInTheDocument();

    await userEvent.clear(screen.getByTestId("approval-search"));
    await userEvent.type(screen.getByTestId("approval-search"), "council");
    await waitFor(() => {
      expect(screen.queryByTestId("name-conv-awaiting-2")).not.toBeInTheDocument();
    });
    expect(screen.getByTestId("name-gc-1")).toHaveTextContent("Launch Council");
  });

  it("names the member whose turn it is, not their raw id", async () => {
    serve({
      groups: [{ ...base, conversationId: "gc-turn", groupId: "grp1", pauseType: "HUMAN_TURN", pendingMemberId: "member-ana" }],
    });
    server.use(
      http.get("*/groupstore/groups/grp1", () =>
        HttpResponse.json({
          name: "Launch Council",
          style: "ROUND_TABLE",
          members: [{ agentId: "member-ana", displayName: "Ana Alvarez", memberType: "HUMAN" }],
        }),
      ),
    );
    renderWithProviders(<ApprovalsPage />);
    expect(await screen.findByText(/Waiting on Ana Alvarez to speak/)).toBeInTheDocument();
  });

  it("lays the table out as cards on a phone, so the actions sit with their row", async () => {
    serve({ regular: [{ ...base, conversationId: "conv-1", agentId: "agent-a" }] });
    renderWithProviders(<ApprovalsPage />);
    const table = await screen.findByTestId("approval-queue-table");
    expect(table.className).toContain("max-md:block");
    expect(table.querySelector("thead")!.className).toContain("max-md:hidden");
  });
});

describe("ApprovalsPage — reviewing a group pause", () => {
  it("shows the phase and the latest entries, and decides with a note", async () => {
    const sent: { body: { decision?: { verdict?: string; note?: string } } | null } = { body: null };
    serve({ groups: [{ ...base, conversationId: "gc-1", groupId: "grp1" }] });
    server.use(
      http.get("*/groups/grp1/conversations/gc-1", () =>
        HttpResponse.json({
          id: "gc-1",
          groupId: "grp1",
          state: "AWAITING_APPROVAL",
          originalQuestion: "Should we ship on Friday?",
          pausedPhaseName: "Synthesis",
          pausedAt: base.pausedAt,
          transcript: [
            { speakerAgentId: "a", speakerDisplayName: "Ana", content: "Friday is risky.", phaseIndex: 0, phaseName: "Opinion", type: "OPINION", timestamp: "t", errorReason: null, targetAgentId: null },
          ],
        }),
      ),
      http.get("*/groups/:groupId/conversations/gc-1/approval-status", () =>
        HttpResponse.json({ groupConversationId: "gc-1", state: "AWAITING_APPROVAL", pausedAt: base.pausedAt, pauseType: "RULE" }),
      ),
      http.post("*/groups/:groupId/conversations/:gcId/approve", async ({ request }) => {
        sent.body = (await request.json()) as typeof sent.body;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<ApprovalsPage />);

    await userEvent.click(await screen.findByTestId("review-gc-1"));
    const context = await screen.findByTestId("group-review-context-gc-1");
    await waitFor(() => expect(context).toHaveTextContent("Should we ship on Friday?"));
    expect(context).toHaveTextContent("Synthesis");
    expect(within(context).getByTestId("group-review-entries")).toHaveTextContent("Friday is risky.");

    // The banner inside the review is the one with the note field.
    const row = screen.getByTestId("group-review-row-gc-1");
    await userEvent.click(within(row).getByTestId("toggle-note"));
    await userEvent.type(within(row).getByTestId("approval-note"), "Ship after the freeze lifts");
    await userEvent.click(within(row).getByTestId("approve-button"));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(sent.body?.decision?.verdict).toBe("APPROVED"));
    expect(sent.body?.decision?.note).toBe("Ship after the freeze lifts");
  });
});
