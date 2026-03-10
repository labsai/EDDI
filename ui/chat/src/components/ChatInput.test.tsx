import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ChatInput } from "./ChatInput";
import { ChatProvider } from "@/store/chat-store";

function renderInput(props = {}) {
  const onSend = vi.fn();
  const result = render(
    <ChatProvider>
      <ChatInput onSend={onSend} {...props} />
    </ChatProvider>,
  );
  return { ...result, onSend };
}

describe("ChatInput", () => {
  it("renders a textarea and send button", () => {
    renderInput();
    expect(screen.getByTestId("chat-input")).toBeInTheDocument();
    expect(screen.getByTestId("chat-send")).toBeInTheDocument();
  });

  it("calls onSend on Enter key", () => {
    const { onSend } = renderInput();
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).toHaveBeenCalledWith("Hello");
  });

  it("does NOT send on Shift+Enter", () => {
    const { onSend } = renderInput();
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: true });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("does NOT send when input is empty", () => {
    const { onSend } = renderInput();
    const textarea = screen.getByTestId("chat-input");
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("does NOT send when disabled", () => {
    const { onSend } = renderInput({ disabled: true });
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("clears input after send", () => {
    renderInput();
    const textarea = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(textarea.value).toBe("");
  });
});
