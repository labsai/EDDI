import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { Composer, SecretInput, SECRET_BUBBLE } from "@eddi/conversation";
import { ChatProvider, useChatDispatch } from "@/store/chat-store";
import type { ChatAction } from "@/store/chat-store";

/**
 * T2.5 — the composer's modes in the widget: text, the user's lock (secret
 * mode) and the field an agent asked for. A secret is never echoed into the
 * clear textarea, never kept as a draft, and sent as typed.
 */

let dispatchRef: ((action: ChatAction) => void) | null = null;
function Grab() {
  dispatchRef = useChatDispatch();
  return null;
}

function renderComposer() {
  const onSend = vi.fn();
  render(
    <ChatProvider>
      <Grab />
      <Composer onSend={onSend} conversationId="conv-1" />
    </ChatProvider>,
  );
  return { onSend };
}

const input = () => screen.getByTestId("chat-input") as HTMLInputElement | HTMLTextAreaElement;
const lock = () => screen.getByTestId("chat-secret-toggle");

afterEach(() => {
  dispatchRef = null;
  localStorage.clear();
  sessionStorage.clear();
});

describe("Composer modes (widget)", () => {
  it("switching the lock off drops the secret instead of showing it in the textarea", () => {
    renderComposer();
    fireEvent.click(lock());
    expect(input()).toHaveAttribute("type", "password");
    fireEvent.change(input(), { target: { value: "hunter2" } });

    fireEvent.click(lock());
    expect(input().tagName).toBe("TEXTAREA");
    expect(input().value).toBe("");
    expect(document.body.textContent).not.toContain("hunter2");

    // And it does not come back with the lock.
    fireEvent.click(lock());
    expect(input().value).toBe("");
  });

  it("keeps the text draft across a trip into secret mode, without putting it in the masked field", () => {
    renderComposer();
    fireEvent.change(input(), { target: { value: "my question" } });
    fireEvent.click(lock());
    expect(input()).toHaveAttribute("type", "password");
    expect(input().value).toBe("");
    fireEvent.click(lock());
    expect(input().value).toBe("my question");
  });

  it("sends a secret as typed and flagged, then returns to the text draft", () => {
    const { onSend } = renderComposer();
    fireEvent.change(input(), { target: { value: "draft" } });
    fireEvent.click(lock());
    fireEvent.change(input(), { target: { value: "  sk-123  " } });
    fireEvent.keyDown(input(), { key: "Enter" });

    expect(onSend).toHaveBeenCalledWith("  sk-123  ", true);
    expect(input().tagName).toBe("TEXTAREA");
    expect(input().value).toBe("draft");
    expect(lock()).toHaveAttribute("aria-pressed", "false");
  });

  it("does not send an empty secret", () => {
    const { onSend } = renderComposer();
    fireEvent.click(lock());
    fireEvent.keyDown(input(), { key: "Enter" });
    expect(onSend).not.toHaveBeenCalled();
    expect(screen.getByTestId("chat-send")).toBeDisabled();
  });

  it("puts a refused secret back into the masked field, never the textarea", () => {
    renderComposer();
    fireEvent.change(input(), { target: { value: "visible draft" } });
    // A secret turn the server refused (409): the controller withdraws it, and
    // the reducer hands the draft back with the lock switched on.
    act(() => {
      dispatchRef!({
        type: "ADD_MESSAGE",
        message: { id: "u1", role: "user", content: SECRET_BUBBLE, timestamp: 1 },
      });
    });
    act(() => {
      dispatchRef!({ type: "WITHDRAW_LAST_USER_MESSAGE", messageId: "u1", draft: "sk-refused", wasSecret: true });
    });
    expect(input()).toHaveAttribute("type", "password");
    expect(input().value).toBe("sk-refused");
    fireEvent.click(lock());
    expect(input().value).toBe("visible draft");
    expect(document.body.textContent).not.toContain("sk-refused");
  });

  it("keeps no secret in storage", () => {
    const { onSend } = renderComposer();
    fireEvent.click(lock());
    fireEvent.change(input(), { target: { value: "sk-storage-probe" } });
    fireEvent.keyDown(input(), { key: "Enter" });
    expect(onSend).toHaveBeenCalled();
    const stored = [
      ...Object.keys(localStorage).map((k) => localStorage.getItem(k)),
      ...Object.keys(sessionStorage).map((k) => sessionStorage.getItem(k)),
    ].join("\n");
    expect(stored).not.toContain("sk-storage-probe");
  });
});

describe("SecretInput (the field an agent asked for)", () => {
  function renderField(subType?: string) {
    const onSend = vi.fn();
    render(
      <ChatProvider>
        <SecretInput subType={subType} label="Key" onSend={onSend} />
      </ChatProvider>,
    );
    return { onSend, field: screen.getByTestId("secret-input-field") as HTMLInputElement };
  }

  it("masks and flags only a password field, sent as typed", () => {
    const { onSend, field } = renderField("Password");
    expect(field).toHaveAttribute("type", "password");
    fireEvent.change(field, { target: { value: " pw " } });
    fireEvent.keyDown(field, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith(" pw ", true);
  });

  it("an e-mail field is an ordinary turn of type email", () => {
    const { onSend, field } = renderField("email");
    expect(field).toHaveAttribute("type", "email");
    fireEvent.change(field, { target: { value: "a@b.c" } });
    fireEvent.keyDown(field, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("a@b.c", false);
  });
});
