import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
import { WorkforceWizard } from "@/pages/workforce/workforce-wizard";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

function renderWizard(query = "") {
  return renderPage(`/workforce/new${query}`, <WorkforceWizard />, "/workforce/new");
}

function fireBeforeUnload() {
  const event = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

/**
 * A configured team — names, prompts and API keys — lived only in component
 * state, so Cancel, Back, a sidebar link or a reload dropped it without a word.
 */
describe("WorkforceWizard — unsaved work", () => {
  it("does not interrupt anything before a template is chosen", async () => {
    const user = userEvent.setup();
    renderWizard();

    expect(fireBeforeUnload()).toBe(false);
    await user.click(screen.getByRole("button", { name: /^back$/i }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("guards reload and asks before Back or Cancel throws the team away", async () => {
    const user = userEvent.setup();
    renderWizard("?template=custom");
    await waitFor(() => expect(screen.getByRole("button", { name: /^next$/i })).toBeEnabled());

    expect(fireBeforeUnload()).toBe(true);

    await user.click(screen.getByRole("button", { name: /^back$/i }));
    expect(await screen.findByRole("alertdialog")).toHaveTextContent("Leave without creating?");
    await user.click(screen.getByTestId("unsaved-cancel"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();

    await user.click(screen.getByRole("link", { name: /cancel/i }));
    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
  });
});

describe("WorkforceWizard — providers", () => {
  it("does not offer Vertex AI, which setup cannot configure", async () => {
    const user = userEvent.setup();
    renderWizard("?template=custom");
    await waitFor(() => expect(screen.getByRole("button", { name: /^next$/i })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: /^next$/i }));

    const providers = await screen.findAllByLabelText(/LLM provider/i);
    const values = within(providers[0]!)
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);
    expect(values).toContain("anthropic");
    expect(values).not.toContain("gemini-vertex");
  });
});
