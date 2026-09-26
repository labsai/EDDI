import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { SecretInput } from "./SecretInput";
import { ChatProvider } from "@/store/chat-store";

function renderField(subType?: string) {
  const onSend = vi.fn();
  render(
    <ChatProvider>
      <SecretInput subType={subType} label="Field" onSend={onSend} />
    </ChatProvider>,
  );
  const field = screen.getByTestId("secret-input-field") as HTMLInputElement;
  fireEvent.change(field, { target: { value: "value-1" } });
  fireEvent.keyDown(field, { key: "Enter", shiftKey: false });
  return { onSend, field };
}

describe("SecretInput", () => {
  it("masks a password field and sends it as secret", () => {
    const { onSend, field } = renderField("password");
    expect(field.type).toBe("password");
    expect(screen.getByTestId("secret-input-eye")).toBeInTheDocument();
    expect(onSend).toHaveBeenCalledWith("value-1", true);
  });

  it("treats a missing subType as password", () => {
    const { onSend } = renderField(undefined);
    expect(onSend).toHaveBeenCalledWith("value-1", true);
  });

  it("renders an email field as email, unmasked, and not as a secret", () => {
    // Every subType used to be masked and flagged secretInput, so an e-mail
    // address was hidden while typed and vaulted as though it were a key.
    const { onSend, field } = renderField("email");
    expect(field.type).toBe("email");
    expect(screen.queryByTestId("secret-input-eye")).not.toBeInTheDocument();
    expect(screen.getByTestId("secret-input-label")).not.toHaveTextContent("🔒");
    expect(onSend).toHaveBeenCalledWith("value-1", false);
  });

  it("renders a text field as plain text and not as a secret", () => {
    const { onSend, field } = renderField("text");
    expect(field.type).toBe("text");
    expect(onSend).toHaveBeenCalledWith("value-1", false);
  });
});
