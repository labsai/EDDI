import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { server } from "@/test/mocks/server";

/**
 * The conversation-detail banner is the approval surface an admin reaches from
 * the inbox. Its decision must be bound to the pause the banner rendered, and —
 * when approval-status could not be read — to the pause start the conversation
 * snapshot carries, never sent unbound.
 */

const toastMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: toastMock }));

beforeEach(() => {
  for (const fn of Object.values(toastMock)) fn.mockClear();
});

const PAUSED_AT = "2026-07-01T10:00:00.000Z";

function servePausedConversation() {
  server.use(
    http.get("*/conversationstore/conversations/simple/:id", () =>
      HttpResponse.json({
        agentId: "agent1",
        agentVersion: 1,
        conversationId: "conv-paused",
        conversationState: "AWAITING_HUMAN",
        hitlPausedAt: PAUSED_AT,
        environment: "production",
        conversationSteps: [],
        conversationOutputs: [],
        conversationProperties: {},
      }),
    ),
  );
}

function recordResumes() {
  const bodies: Record<string, unknown>[] = [];
  server.use(
    http.post("*/agents/:conversationId/resume", async ({ request }) => {
      bodies.push((await request.json()) as Record<string, unknown>);
      return new HttpResponse(null, { status: 200 });
    }),
  );
  return bodies;
}

const ruleStatus = (overrides: Record<string, unknown> = {}) => ({
  conversationId: "conv-paused",
  state: "AWAITING_HUMAN",
  pausedAt: PAUSED_AT,
  pauseReason: "Needs sign-off",
  pauseDetails: { type: "RULE", reason: "Needs sign-off", actions: [] },
  ...overrides,
});

function render() {
  return renderPage(
    "/manage/conversationview/conv-paused",
    <ConversationDetailPage />,
    "/manage/conversationview/:id",
  );
}

async function decide(user: ReturnType<typeof userEvent.setup>, button: "approve-button" | "reject-button") {
  await waitFor(() => expect(screen.getByTestId(button)).toBeEnabled());
  await user.click(screen.getByTestId(button));
  const dialog = await screen.findByRole("dialog");
  await user.click(within(dialog).getByRole("button", { name: button === "approve-button" ? "Approve" : "Reject" }));
}

describe("conversation-detail approval banner binding", () => {
  it("sends the pause id of the pause the banner showed", async () => {
    servePausedConversation();
    server.use(
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json(ruleStatus({ pauseId: "1782900000000" })),
      ),
    );
    const resumes = recordResumes();
    const user = userEvent.setup();
    render();

    await decide(user, "approve-button");

    await waitFor(() => expect(resumes).toHaveLength(1));
    expect(resumes[0]).toEqual({ verdict: "APPROVED", pauseId: "1782900000000" });
  });

  it("refuses when the conversation paused again after the banner was rendered", async () => {
    servePausedConversation();
    let reads = 0;
    server.use(
      http.get("*/agents/:conversationId/approval-status", () => {
        reads++;
        // The banner's read shows the rule pause; by the decision it is a later one.
        return HttpResponse.json(reads === 1 ? ruleStatus() : ruleStatus({ pausedAt: "2026-07-01T10:05:00.000Z" }));
      }),
    );
    const resumes = recordResumes();
    const user = userEvent.setup();
    render();

    await decide(user, "approve-button");

    await waitFor(() =>
      expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/changed since you opened it/)),
    );
    expect(resumes).toEqual([]);
  });

  describe("fallback when approval-status could not be read", () => {
    it("binds to the snapshot's pause start and sends once the re-read agrees", async () => {
      servePausedConversation();
      let reads = 0;
      server.use(
        http.get("*/agents/:conversationId/approval-status", () => {
          reads++;
          return reads === 1
            ? HttpResponse.json({ error: "down" }, { status: 500 })
            : HttpResponse.json(ruleStatus());
        }),
      );
      const resumes = recordResumes();
      const user = userEvent.setup();
      render();
      await screen.findByTestId("approval-banner");

      // Approve stays blocked on a failed read; Reject is still offered.
      await decide(user, "reject-button");

      await waitFor(() => expect(resumes).toHaveLength(1));
      expect(resumes[0]).toEqual({ verdict: "REJECTED" });
    });

    it("refuses when the re-read shows a different pause", async () => {
      servePausedConversation();
      let reads = 0;
      server.use(
        http.get("*/agents/:conversationId/approval-status", () => {
          reads++;
          return reads === 1
            ? HttpResponse.json({ error: "down" }, { status: 500 })
            : HttpResponse.json(ruleStatus({ pausedAt: "2026-07-01T10:05:00.000Z" }));
        }),
      );
      const resumes = recordResumes();
      const user = userEvent.setup();
      render();
      await screen.findByTestId("approval-banner");

      await decide(user, "reject-button");

      await waitFor(() => expect(toastMock.error).toHaveBeenCalled());
      expect(resumes).toEqual([]);
    });
  });
});
