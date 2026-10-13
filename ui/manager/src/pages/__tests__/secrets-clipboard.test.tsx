import { describe, it, expect, vi, afterEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretsPage } from "@/pages/secrets";

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }));
vi.mock("sonner", () => ({ toast: toastMock }));

describe("SecretsPage - copy reference without a Clipboard API", () => {
  const original = Object.getOwnPropertyDescriptor(navigator, "clipboard");
  afterEach(() => {
    if (original) Object.defineProperty(navigator, "clipboard", original);
    else delete (navigator as unknown as Record<string, unknown>).clipboard;
    toastMock.error.mockClear();
  });

  it("toasts instead of throwing when navigator.clipboard is unavailable", async () => {
    renderWithProviders(<SecretsPage />, { initialRoute: "/manage/secrets" });
    const btn = await screen.findByTestId("copy-ref-openai-api-key");
    const user = userEvent.setup(); // installs its own clipboard stub; remove it after
    Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
    await user.click(btn);
    expect(toastMock.error).toHaveBeenCalledWith("Failed to copy to clipboard");
  });
});
