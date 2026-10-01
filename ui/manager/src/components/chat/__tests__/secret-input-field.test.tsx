import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { SecretInputField } from "../secret-input-field";

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

    expect(onSend).toHaveBeenCalledWith("sk-secret");
    expect(screen.getByLabelText("API Key")).toHaveValue("sk-secret");
  });
});
