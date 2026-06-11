import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatInput } from "@/components/chat/chat-input";

describe("ChatInput", () => {
  it("renders textarea and send button", () => {
    renderWithProviders(<ChatInput onSend={vi.fn()} />);

    expect(screen.getByTestId("chat-input")).toBeInTheDocument();
    expect(screen.getByTestId("chat-send")).toBeInTheDocument();
  });

  it("send button is disabled when textarea is empty", () => {
    renderWithProviders(<ChatInput onSend={vi.fn()} />);

    expect(screen.getByTestId("chat-send")).toBeDisabled();
  });

  it("send button enables when text is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatInput onSend={vi.fn()} />);

    await user.type(screen.getByTestId("chat-input"), "Hello");
    expect(screen.getByTestId("chat-send")).not.toBeDisabled();
  });

  it("calls onSend with trimmed text on button click", async () => {
    const onSend = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ChatInput onSend={onSend} />);

    await user.type(screen.getByTestId("chat-input"), "  Hello World  ");
    await user.click(screen.getByTestId("chat-send"));

    expect(onSend).toHaveBeenCalledWith("Hello World");
  });

  it("clears input after sending", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatInput onSend={vi.fn()} />);

    const input = screen.getByTestId("chat-input");
    await user.type(input, "Test message");
    await user.click(screen.getByTestId("chat-send"));

    expect(input).toHaveValue("");
  });

  it("sends on Enter key press", async () => {
    const onSend = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ChatInput onSend={onSend} />);

    await user.type(screen.getByTestId("chat-input"), "Enter test{Enter}");
    expect(onSend).toHaveBeenCalledWith("Enter test");
  });

  it("does NOT send on Shift+Enter (allows newline)", async () => {
    const onSend = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ChatInput onSend={onSend} />);

    await user.type(
      screen.getByTestId("chat-input"),
      "Line 1{Shift>}{Enter}{/Shift}Line 2"
    );
    expect(onSend).not.toHaveBeenCalled();
  });

  it("does not send when disabled", async () => {
    const onSend = vi.fn();
    userEvent.setup();
    renderWithProviders(<ChatInput onSend={onSend} disabled />);

    const input = screen.getByTestId("chat-input");
    expect(input).toBeDisabled();
  });

  it("does not send when only whitespace is entered", async () => {
    const onSend = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ChatInput onSend={onSend} />);

    await user.type(screen.getByTestId("chat-input"), "   ");
    expect(screen.getByTestId("chat-send")).toBeDisabled();
  });

  it("shows spinner icon when isProcessing is true", () => {
    renderWithProviders(
      <ChatInput onSend={vi.fn()} isProcessing />
    );

    // When processing, should show animate-spin icon
    const sendBtn = screen.getByTestId("chat-send");
    const spinner = sendBtn.querySelector(".animate-spin");
    expect(spinner).not.toBeNull();
  });

  it("does not send when isProcessing", async () => {
    const onSend = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ChatInput onSend={onSend} isProcessing />
    );

    // Type text but isProcessing should prevent sending
    await user.type(screen.getByTestId("chat-input"), "Hello");
    await user.click(screen.getByTestId("chat-send"));
    expect(onSend).not.toHaveBeenCalled();
  });

  it("has proper placeholder text", () => {
    renderWithProviders(<ChatInput onSend={vi.fn()} />);

    const input = screen.getByTestId("chat-input");
    expect(input).toHaveAttribute("placeholder");
  });

  it("has aria-label on send button", () => {
    renderWithProviders(<ChatInput onSend={vi.fn()} />);

    const sendBtn = screen.getByTestId("chat-send");
    expect(sendBtn).toHaveAttribute("aria-label");
  });
});
