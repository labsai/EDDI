import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorPage } from "../operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";

/**
 * The History tab, and restoring a conversation on mount.
 *
 * The bug being fixed: the operator chat lived only in memory, so navigating
 * away and back lost the transcript — an investigation still running on the
 * server became unreachable from the UI.
 */
vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: [],
    method: "none" as const,
    login: () => {},
    logout: () => {},
  }),
  useHasRole: () => true,
}));

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;

function activeConfig(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return {
    ...defaultOperatorConfig("Body."),
    enabled: true,
    agentId: "op-1",
    version: 2,
    ...overrides,
  };
}

function descriptor(id: string, lastModifiedOn: number, conversationState = "READY") {
  return {
    resource: `eddi://ai.labs.conversation/conversationstore/conversations/${id}`,
    createdOn: lastModifiedOn,
    lastModifiedOn,
    conversationState,
    conversationStepSize: 2,
    agentResource: "eddi://ai.labs.agent/agentstore/agents/op-1?version=2",
  };
}

/** A transcript whose opening question identifies it. */
function transcript(question: string, answer: string, state = "READY") {
  return {
    agentId: "op-1",
    agentVersion: 2,
    conversationId: "c",
    environment: "production",
    conversationState: state,
    conversationSteps: [{ conversationStep: [{ key: "input:initial", value: question }] }],
    conversationOutputs: [{ output: [{ type: "text", text: answer }] }],
  };
}

describe("OperatorPage — conversation history", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    // Order matters: reset() writes the "do not restore" tombstone, so storage
    // is cleared after it — see use-operator-chat-hydrate.test.tsx.
    useOperatorChatStore.getState().reset();
    sessionStorage.clear();
    server.resetHandlers();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
      http.get(VAR_URL, () =>
        HttpResponse.json({
          key: OPERATOR_VARIABLE_KEY,
          value: JSON.stringify(activeConfig()),
        }),
      ),
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
  });

  it("lists the operator's past conversations under the History tab", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json([descriptor("conv-a", 5000), descriptor("conv-b", 1000)]),
      ),
      http.get("*/conversationstore/conversations/simple/conv-a", () =>
        HttpResponse.json(transcript("why is agent-9 failing?", "Its vault key is missing.")),
      ),
      http.get("*/conversationstore/conversations/simple/conv-b", () =>
        HttpResponse.json(transcript("what is deployed?", "Three agents.")),
      ),
    );

    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-tab-history"));

    // Scoped to the list: mounting also RESTORES the newest conversation into
    // the chat (that is the other half of this feature), and the chat stays
    // mounted behind the History tab — so its opening question is legitimately
    // on the page twice, and an unscoped query fails on the duplicate.
    const list = await screen.findByTestId("operator-history-list");
    expect(await within(list).findByText("why is agent-9 failing?")).toBeInTheDocument();
    expect(await within(list).findByText("what is deployed?")).toBeInTheDocument();
  });

  /**
   * The descriptor store's sort is a per-filter backend setting, not a
   * newest-first contract — so the list sorts for itself rather than rendering
   * whatever order arrived.
   */
  it("shows the newest conversation first whatever order the backend returns", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json([descriptor("conv-old", 1000), descriptor("conv-new", 9000)]),
      ),
      http.get("*/conversationstore/conversations/simple/conv-old", () =>
        HttpResponse.json(transcript("the older one", "old answer")),
      ),
      http.get("*/conversationstore/conversations/simple/conv-new", () =>
        HttpResponse.json(transcript("the newer one", "new answer")),
      ),
    );

    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-tab-history"));

    const list = await screen.findByTestId("operator-history-list");
    await within(list).findByText("the newer one");
    const rows = within(list).getAllByRole("button");
    expect(rows[0]).toHaveAttribute("data-testid", "operator-conversation-conv-new");
  });

  /**
   * The newest conversation is deliberately NOT the one clicked.
   *
   * With a single-entry fixture the mount restore had already rendered that
   * exact transcript before the click, so this test passed with
   * `selectConversation`'s body replaced by `return` — it was asserting the
   * restore, not the pick. The swap is what proves the click did anything.
   */
  it("loads a picked conversation into the chat and switches back to it", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json([descriptor("conv-newest", 9000), descriptor("conv-a", 5000)]),
      ),
      http.get("*/conversationstore/conversations/simple/conv-newest", () =>
        HttpResponse.json(transcript("the restored one", "restored answer")),
      ),
      http.get("*/conversationstore/conversations/simple/conv-a", () =>
        HttpResponse.json(transcript("why is agent-9 failing?", "Its vault key is missing.")),
      ),
    );

    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-tab-history"));
    await userEvent.click(await screen.findByTestId("operator-conversation-conv-a"));

    // Back on Chat, showing the transcript — the answer only exists there.
    await waitFor(() =>
      expect(screen.getByTestId("operator-tab-chat")).toHaveAttribute("aria-selected", "true"),
    );
    expect(await screen.findByText("Its vault key is missing.")).toBeInTheDocument();
    expect(useOperatorChatStore.getState().conversationId).toBe("conv-a");
    // The transcript the mount restore had put up must be GONE — a pick
    // replaces, it does not append.
    expect(screen.queryByText("restored answer")).not.toBeInTheDocument();
  });

  /**
   * A conversation waiting on a decision must come back decidable, not just
   * readable — otherwise an approval left behind by a closed tab is stranded.
   */
  it("restores the pause when a paused conversation is picked", async () => {
    server.use(
      // Again the newest is a different, unpaused conversation, so isPaused
      // cannot already be true from the mount restore when the click happens.
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json([descriptor("conv-newest", 9000), descriptor("conv-paused", 5000, "AWAITING_HUMAN")]),
      ),
      http.get("*/conversationstore/conversations/simple/conv-newest", () =>
        HttpResponse.json(transcript("something else", "an answer")),
      ),
      http.get("*/conversationstore/conversations/simple/conv-paused", () =>
        HttpResponse.json(
          transcript("deploy agent-9", "I need your approval to deploy.", "AWAITING_HUMAN"),
        ),
      ),
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json({ paused: true, pauseReason: "Deploying agent-9" }),
      ),
    );

    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-tab-history"));
    await userEvent.click(await screen.findByTestId("operator-conversation-conv-paused"));

    await waitFor(() => expect(useOperatorChatStore.getState().isPaused).toBe(true));
  });

  /**
   * The reason this work exists: mounting the page must bring back the
   * conversation the tab was already working in, without the admin doing
   * anything.
   */
  it("restores the stored conversation on mount, with no interaction", async () => {
    sessionStorage.setItem("eddi.operator.conversationId", "conv-stored");
    useOperatorChatStore.setState({ conversationId: "conv-stored" });
    server.use(
      http.get("*/conversationstore/conversations/simple/conv-stored", () =>
        HttpResponse.json(transcript("what changed last night?", "A redeploy at 02:14.")),
      ),
    );

    renderWithProviders(<OperatorPage />);

    expect(await screen.findByText("A redeploy at 02:14.")).toBeInTheDocument();
  });

  /**
   * The tablist declared the ARIA roles but not the behaviour: both tabs sat in
   * the Tab sequence, arrow keys did nothing, and neither panel was associated
   * with its tab. That announces as tabs to a screen reader and behaves as two
   * loose buttons.
   */
  it("implements the tab pattern for keyboard and screen-reader users", async () => {
    server.use(http.get("*/conversationstore/conversations", () => HttpResponse.json([])));
    renderWithProviders(<OperatorPage />);

    const chatTab = await screen.findByTestId("operator-tab-chat");
    const historyTab = screen.getByTestId("operator-tab-history");

    // Roving tabIndex: only the selected tab is in the Tab sequence.
    expect(chatTab).toHaveAttribute("tabindex", "0");
    expect(historyTab).toHaveAttribute("tabindex", "-1");
    // Each tab names the panel it controls, and the panel names it back.
    expect(chatTab).toHaveAttribute("aria-controls", "operator-tabpanel-chat");
    expect(document.getElementById("operator-tabpanel-chat")).toHaveAttribute(
      "aria-labelledby",
      "operator-tab-chat",
    );

    // Arrow keys move selection.
    chatTab.focus();
    await userEvent.keyboard("{ArrowRight}");
    await waitFor(() => expect(historyTab).toHaveAttribute("aria-selected", "true"));
    expect(historyTab).toHaveAttribute("tabindex", "0");
    expect(chatTab).toHaveAttribute("tabindex", "-1");
  });

  /**
   * Every lifecycle state is selectable — reading a finished investigation is
   * the point of the tab — but ENDED, ERROR and IN_PROGRESS cannot take another
   * turn, so the composer must close rather than accept a message that will
   * fail at the backend.
   */
  it("opens a finished conversation read-only", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json([descriptor("conv-dead", 5000, "ENDED")]),
      ),
      http.get("*/conversationstore/conversations/simple/conv-dead", () =>
        HttpResponse.json(transcript("what happened here?", "It ended.", "ENDED")),
      ),
    );

    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-tab-history"));
    await userEvent.click(await screen.findByTestId("operator-conversation-conv-dead"));

    // The transcript is readable...
    expect(await screen.findByText("It ended.")).toBeInTheDocument();
    // ...and the composer is not.
    await waitFor(() => expect(screen.getByTestId("operator-input")).toBeDisabled());
  });

  it("shows an empty state when the operator has no conversations yet", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () => HttpResponse.json([])),
    );

    renderWithProviders(<OperatorPage />);
    await userEvent.click(await screen.findByTestId("operator-tab-history"));

    expect(await screen.findByText(/no past conversations/i)).toBeInTheDocument();
  });
});
