import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { SecretInputField } from "@eddi/app-kit/components/chat/secret-input-field";

/**
 * The field an agent's `inputField` output asks for. Its visible label used to
 * be a `div` with no association to the input, so assistive technology
 * announced an unnamed password box.
 */
describe("SecretInputField — accessible name", () => {
  it("is named by the visible label the agent sent", () => {
    renderWithProviders(<SecretInputField label="API Key" placeholder="sk-…" onSend={vi.fn()} />);

    expect(screen.getByLabelText("API Key")).toBe(screen.getByTestId("secret-input-field"));
  });

  it("falls back to the placeholder when no label was sent", () => {
    renderWithProviders(<SecretInputField placeholder="Paste your token" onSend={vi.fn()} />);

    expect(screen.queryByTestId("secret-input-label")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Paste your token")).toBe(screen.getByTestId("secret-input-field"));
  });

  it("falls back to the default secret prompt when neither was sent", () => {
    renderWithProviders(<SecretInputField onSend={vi.fn()} />);

    expect(screen.getByLabelText("Enter secret value...")).toBe(
      screen.getByTestId("secret-input-field"),
    );
  });

  it("keeps the value when the send reports it did not go out", async () => {
    const onSend = vi.fn(() => false);
    renderWithProviders(<SecretInputField label="API Key" onSend={onSend} />);

    await userEvent.type(screen.getByLabelText("API Key"), "sk-secret{Enter}");

    expect(onSend).toHaveBeenCalledWith("sk-secret", true);
    expect(screen.getByLabelText("API Key")).toHaveValue("sk-secret");
  });
});

/** T2.5 — the field mode follows the same rules as the widget's (`composer-mode.ts`). */
describe("SecretInputField — field mode", () => {
  it("masks a password field and sends it as typed, flagged secret", async () => {
    const onSend = vi.fn();
    renderWithProviders(<SecretInputField label="API Key" subType="password" onSend={onSend} />);
    const field = screen.getByLabelText("API Key");
    expect(field).toHaveAttribute("type", "password");

    await userEvent.type(field, "  sk-1  {Enter}");

    expect(onSend).toHaveBeenCalledWith("  sk-1  ", true);
    expect(field).toHaveValue("");
  });

  it("an e-mail field is an ordinary turn: type email, unmasked, no reveal toggle, not secret", async () => {
    const onSend = vi.fn();
    renderWithProviders(<SecretInputField label="E-mail" subType="email" onSend={onSend} />);
    const field = screen.getByLabelText("E-mail");
    expect(field).toHaveAttribute("type", "email");
    expect(screen.queryByTestId("secret-input-eye")).toBeNull();

    await userEvent.type(field, " me@example.com {Enter}");

    expect(onSend).toHaveBeenCalledWith("me@example.com", false);
  });

  it("offers a way back to the ordinary composer when the host gives one", async () => {
    const onCancel = vi.fn();
    const { unmount } = renderWithProviders(<SecretInputField label="Key" onSend={vi.fn()} />);
    expect(screen.queryByTestId("secret-input-cancel")).toBeNull();
    unmount();

    renderWithProviders(<SecretInputField label="Key" onSend={vi.fn()} onCancel={onCancel} />);
    await userEvent.click(screen.getByTestId("secret-input-cancel"));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
