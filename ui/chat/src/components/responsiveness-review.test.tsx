/* ──────────────────────────────────────────────
   Regressions from the 2026-10 responsiveness / UX review
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { QuickReplies } from "./QuickReplies";
import { MessageBubble } from "./MessageBubble";
import { ChatInput } from "./ChatInput";
import { indicatorStatusText } from "./Indicators";
import { ChatProvider, useChatDispatch } from "@/store/chat-store";
import { stepsToMessages } from "@/api/snapshot";
import { setLocale } from "@/i18n";

afterEach(() => {
  vi.restoreAllMocks();
  setLocale("en");
});

describe("QuickReplies", () => {
  it("renders the buttons disabled while a click would be dropped", () => {
    const onSelect = vi.fn();
    render(<QuickReplies replies={[{ value: "Yes" }]} onSelect={onSelect} disabled />);
    const btn = screen.getByRole("button", { name: "Yes" });
    expect(btn).toBeDisabled();
    fireEvent.click(btn);
    expect(onSelect).not.toHaveBeenCalled();
  });
});

describe("stepsToMessages ids", () => {
  const step = (data: Array<{ key: string; value: unknown }>) => ({
    conversationStep: data,
    timestamp: "2026-10-17T10:00:00Z",
  });
  const steps = [
    step([
      { key: "input:initial", value: "hi" },
      { key: "output:text:greet", value: [{ type: "text", text: "Hello" }, { type: "text", text: "Again" }] },
    ]),
    step([{ key: "input:initial", value: "next" }]),
  ];

  it("are unique within a transcript", () => {
    const ids = stepsToMessages(steps).map((m) => m.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("are the same when the same conversation is rebuilt (undo/redo keeps bubbles mounted)", () => {
    expect(stepsToMessages(steps).map((m) => m.id)).toEqual(stepsToMessages(steps).map((m) => m.id));
  });
});

describe("MessageBubble", () => {
  it("wraps a markdown table in its own horizontal scroller", () => {
    const { container } = render(
      <MessageBubble
        message={{ id: "1", role: "agent", content: "| a | b |\n|---|---|\n| 1 | 2 |", timestamp: 0 }}
      />,
    );
    const table = container.querySelector("table");
    expect(table?.parentElement).toHaveClass("table-scroll");
  });

  it("lets the browser pick the text direction of a message", () => {
    const { container } = render(
      <MessageBubble message={{ id: "1", role: "user", content: "مرحبا", timestamp: 0 }} />,
    );
    expect(container.querySelector("p")).toHaveAttribute("dir", "auto");
  });
});

describe("indicator status text", () => {
  it("is translated", () => {
    setLocale("de");
    expect(indicatorStatusText(false, null)).toBe("Denke nach…");
    expect(indicatorStatusText(false, "calculator")).toBe("Verwende calculator…");
  });
});

describe("ChatInput on a touch screen", () => {
  it("treats Enter as a newline, leaving sending to the Send button", () => {
    vi.spyOn(window, "matchMedia").mockImplementation(
      (query: string) =>
        ({
          matches: query === "(pointer: coarse)",
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    );
    const onSend = vi.fn();
    render(
      <ChatProvider>
        <ChatInput onSend={onSend} />
      </ChatProvider>,
    );
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "line one" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("chat-send"));
    expect(onSend).toHaveBeenCalledWith("line one", false);
  });
});

describe("ChatInput focus handoff", () => {
  it("holds a focus request until the turn has finished", () => {
    let dispatch!: ReturnType<typeof useChatDispatch>;
    function Grab() {
      dispatch = useChatDispatch();
      return null;
    }
    const ui = (focusSignal: number) => (
      <ChatProvider>
        <Grab />
        <ChatInput onSend={vi.fn()} focusSignal={focusSignal} />
      </ChatProvider>
    );
    const { rerender } = render(ui(0));
    act(() => dispatch({ type: "SET_PROCESSING", value: true }));

    // A quick reply asks for focus as its turn starts...
    rerender(ui(1));
    expect(screen.getByTestId("chat-input")).not.toHaveFocus();

    // ...and gets it once the turn is over, not while the reply is arriving.
    act(() => dispatch({ type: "SET_PROCESSING", value: false }));
    expect(screen.getByTestId("chat-input")).toHaveFocus();
  });
});
