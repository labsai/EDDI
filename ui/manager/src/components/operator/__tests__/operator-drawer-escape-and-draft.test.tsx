import { describe, it, expect, beforeEach, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorDrawer } from "../operator-drawer";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";
import { useOperatorDrawerStore } from "@/hooks/use-operator-drawer";

vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: [],
    method: "none",
    login: () => {},
    logout: () => {},
  }),
  useHasRole: () => true,
}));

describe("OperatorDrawer — Escape and the unsent draft", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useOperatorChatStore.getState().reset();
    useOperatorChatStore.setState({ draft: "" });
    useOperatorDrawerStore.setState({ isOpen: true });
    server.use(
      http.get(`*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`, () =>
        HttpResponse.json({
          key: OPERATOR_VARIABLE_KEY,
          value: JSON.stringify({ ...defaultOperatorConfig("Body."), enabled: true, agentId: "op-1", version: 2 }),
        }),
      ),
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
      http.get("*/administration/:env/deploymentstatus/:agentId", () => HttpResponse.json({ status: "READY" })),
    );
  });

  it("keeps a half-written message across closing and reopening the drawer", async () => {
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    const input = await screen.findByTestId("operator-input");
    await userEvent.type(input, "Why did the nightly job fail");

    await userEvent.click(screen.getByTestId("operator-drawer-close"));
    expect(screen.queryByTestId("operator-input")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("operator-drawer-fab"));
    expect(await screen.findByTestId("operator-input")).toHaveValue("Why did the nightly job fail");
  });

  it("does not close on an Escape that belongs to a dialog opened over it", async () => {
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    await screen.findByTestId("operator-input");

    const dialog = document.createElement("div");
    dialog.setAttribute("role", "alertdialog");
    document.body.appendChild(dialog);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByTestId("operator-drawer-panel")).toBeInTheDocument();
    dialog.remove();

    // Nor one something else already consumed.
    const consumed = new KeyboardEvent("keydown", { key: "Escape", cancelable: true });
    consumed.preventDefault();
    window.dispatchEvent(consumed);
    expect(screen.getByTestId("operator-drawer-panel")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("operator-drawer-panel")).not.toBeInTheDocument());
  });

  it("does not send on the Enter that confirms an IME composition", async () => {
    renderWithProviders(<OperatorDrawer />, { initialRoute: "/manage/agents" });
    const input = await screen.findByTestId("operator-input");
    fireEvent.change(input, { target: { value: "你好" } });

    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    // A send would have emptied the composer.
    expect(input).toHaveValue("你好");
    expect(useOperatorChatStore.getState().draft).toBe("你好");
  });
});
