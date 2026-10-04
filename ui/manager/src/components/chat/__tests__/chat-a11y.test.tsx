import { describe, it, expect, beforeEach, beforeAll, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatPanel } from "../chat-panel";
import { SecretInputField } from "../secret-input-field";
import { useChatStore } from "@/hooks/use-chat";
import { useDebugStore } from "@/hooks/use-debug-events";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

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

  it("exposes the transcript as a polite live log", () => {
    renderWithProviders(<ChatPanel />);
    const log = screen.getByRole("log");
    expect(log).toHaveAttribute("aria-live", "polite");
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

  it("opens the list from the trigger with ArrowDown", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);
    screen.getByTestId("agent-selector").focus();
    await user.keyboard("{ArrowDown}");
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
  });
});
