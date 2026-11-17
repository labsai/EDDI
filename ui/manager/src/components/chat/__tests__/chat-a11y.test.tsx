import { describe, it, expect, beforeEach, beforeAll, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { SecretInputField } from "@eddi/app-kit/components/chat/secret-input-field";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";
import { server } from "@eddi/app-kit/test/mocks/server";
import { http, HttpResponse } from "msw";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";

/**
 * T1.12 — contrast. Muted text faded further with an opacity modifier
 * (`text-muted-foreground/60`) falls below 4.5:1 on the card and the
 * background in both themes. The chat's own text and controls use the token
 * as it is; a faded colour is allowed only on a purely decorative icon.
 */
describe("chat contrast", () => {
  const CHAT_DIR = resolve(process.cwd(), "..", "packages", "app-kit", "src", "components", "chat");
  const FILES = ["chat-panel.tsx", "chat-message.tsx", "attachment-chip.tsx", "input-hint.tsx", "chat-rating.tsx"];

  it("never fades muted text or a control below the token", () => {
    const offenders = FILES.flatMap((file) =>
      readFileSync(join(CHAT_DIR, file), "utf8")
        .split("\n")
        .map((line, i) => ({ line, at: `${file}:${i + 1}` }))
        .filter(({ line }) => /text-muted-foreground\/[1-8]0\b/.test(line))
        // A decorative illustration (the empty state's large bot) may be faint.
        .filter(({ line }) => !/<Bot className="mx-auto h-12 w-12/.test(line))
        .map(({ at }) => at),
    );
    expect(offenders).toEqual([]);
  });

  it("gives the conversation-info toggle a name and its state", () => {
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    renderWithProviders(<ChatPanel />);
    const toggle = screen.getByTestId("context-toggle");
    expect(toggle).toHaveAccessibleName("Show conversation info");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAccessibleName("Hide conversation info");
    expect(toggle).toHaveAttribute("aria-expanded", "true");
  });
});

describe("SecretInputField — IME and eye toggle", () => {
  it("does not send on the Enter that confirms an IME composition", () => {
    const onSend = vi.fn(() => true);
    renderWithProviders(<SecretInputField label="Key" onSend={onSend} />);
    const input = screen.getByLabelText("Key");

    fireEvent.change(input, { target: { value: "にほん" } });
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(onSend).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: "Enter" });
    expect(onSend).toHaveBeenCalledTimes(1);
  });

  it("names the show/hide toggle and exposes its state", async () => {
    renderWithProviders(<SecretInputField label="Key" onSend={vi.fn()} />);
    const toggle = screen.getByTestId("secret-input-eye");

    expect(toggle).toHaveAccessibleName("Show");
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    await userEvent.setup().click(toggle);
    expect(toggle).toHaveAccessibleName("Hide");
    expect(toggle).toHaveAttribute("aria-pressed", "true");
  });
});

describe("ChatPanel — transcript and agent selector accessibility", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
    useDebugStore.getState().reset();
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
            name: "Customer Support Bot",
            description: "Answers user questions",
          },
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent2?version=1",
            name: "Travel Planner",
            description: "Plans trips",
          },
        ]),
      ),
      http.get("*/deployment/production/agentstore/agents/:id/version/1", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
  });

  it("exposes the transcript as a log, and says replies through one polite status region", () => {
    renderWithProviders(<ChatPanel />);
    const log = screen.getByRole("log");
    // Not live itself: a streamed reply would be read token by token.
    expect(log).toHaveAttribute("aria-live", "off");
    const announcer = screen.getByTestId("reply-announcer");
    expect(announcer).toHaveAttribute("role", "status");
    expect(announcer).toHaveAttribute("aria-live", "polite");
  });

  it("filters agents with the search box and picks one with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);

    await user.click(screen.getByTestId("agent-selector"));
    const search = await screen.findByTestId("agent-search");
    await waitFor(() => expect(search).toHaveFocus());
    await screen.findByText("Travel Planner");

    await user.type(search, "travel");
    expect(screen.queryByText("Customer Support Bot")).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: /Travel Planner/ })).toBeInTheDocument();

    await user.keyboard("{Enter}");
    await waitFor(() => expect(useChatStore.getState().selectedAgentId).toBe("agent2"));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("moves the highlight with the arrow keys and closes on Escape, returning focus", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);
    const trigger = screen.getByTestId("agent-selector");

    await user.click(trigger);
    const search = await screen.findByTestId("agent-search");
    await screen.findByText("Travel Planner");

    expect(search).toHaveAttribute("aria-activedescendant", expect.stringMatching(/-opt-0$/));
    await user.keyboard("{ArrowDown}");
    expect(search).toHaveAttribute("aria-activedescendant", expect.stringMatching(/-opt-1$/));
    await user.keyboard("{ArrowUp}");
    expect(search).toHaveAttribute("aria-activedescendant", expect.stringMatching(/-opt-0$/));

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("ignores Escape and Enter during IME composition", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);

    await user.click(screen.getByTestId("agent-selector"));
    const search = await screen.findByTestId("agent-search");
    await screen.findByText("Travel Planner");

    fireEvent.keyDown(search, { key: "Escape", isComposing: true });
    fireEvent.keyDown(search, { key: "Enter", isComposing: true });
    fireEvent.keyDown(search, { key: "Enter", keyCode: 229 });

    expect(screen.getByRole("listbox")).toBeInTheDocument();
    expect(useChatStore.getState().selectedAgentId).toBeFalsy();
  });

  it("does not send the message input on an IME-confirming Enter, but sends on a normal one", async () => {
    let posted = 0;
    server.use(
      http.post("*/agents/conv1", () => {
        posted += 1;
        return HttpResponse.json({
          conversationOutputs: [{ output: [{ type: "text", text: "got it" }] }],
        });
      }),
    );
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);

    const input = screen.getByTestId("chat-input");
    await waitFor(() => expect(input).toBeEnabled());
    await user.type(input, "にほん");

    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(posted).toBe(0);
    expect(useChatStore.getState().messages).toHaveLength(0);
    expect(input).toHaveValue("にほん");

    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(posted).toBe(1));
  });

  it("opens the list from the trigger with ArrowDown", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);
    screen.getByTestId("agent-selector").focus();
    await user.keyboard("{ArrowDown}");
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
  });
});
