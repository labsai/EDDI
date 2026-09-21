import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";

import { TemplatesPanel } from "../templates-panel";

/**
 * The delete used `window.confirm`, which is the one form of confirmation a
 * jsdom test cannot see: it returns `undefined` unless stubbed, which reads as
 * "cancel", so the delete simply never happened and no assertion could tell a
 * working button from a broken one. That is most of why this file sat at 38%.
 *
 * Templates live in localStorage, so "was it deleted" is a question with a
 * definite answer — these assert on the stored list rather than on the dialog.
 */

const STORAGE_KEY = "workforce-templates";

function seed() {
  const templates = [
    {
      id: "tpl-1",
      name: "Product Review Panel",
      description: "Peer review",
      style: "ROUND_TABLE",
      members: [{ displayName: "Ana", role: "reviewer" }],
      maxRounds: 3,
      createdAt: new Date(0).toISOString(),
    },
    {
      id: "tpl-2",
      name: "Incident Retro",
      description: "After the fact",
      style: "DEBATE",
      members: [{ displayName: "Bo", role: "lead" }],
      maxRounds: 2,
      createdAt: new Date(0).toISOString(),
    },
  ];
  localStorage.setItem(STORAGE_KEY, JSON.stringify(templates));
  return templates;
}

const stored = (): Array<{ id: string }> =>
  JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "[]");

describe("TemplatesPanel delete", () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    vi.clearAllMocks();
  });

  it("deletes nothing until the deletion is confirmed", async () => {
    seed();
    const user = userEvent.setup();
    renderWithProviders(<TemplatesPanel onUseTemplate={vi.fn()} />);

    await user.click(screen.getByTestId("template-delete-tpl-1"));

    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(stored().map((t) => t.id)).toEqual(["tpl-1", "tpl-2"]);
  });

  it("names the template it is about to remove", async () => {
    seed();
    const user = userEvent.setup();
    renderWithProviders(<TemplatesPanel onUseTemplate={vi.fn()} />);

    await user.click(screen.getByTestId("template-delete-tpl-2"));

    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Incident Retro");
  });

  it("removes only the confirmed template", async () => {
    seed();
    const user = userEvent.setup();
    renderWithProviders(<TemplatesPanel onUseTemplate={vi.fn()} />);

    await user.click(screen.getByTestId("template-delete-tpl-1"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(stored().map((t) => t.id)).toEqual(["tpl-2"]));
  });

  it("keeps the template when the dialog is dismissed", async () => {
    seed();
    const user = userEvent.setup();
    renderWithProviders(<TemplatesPanel onUseTemplate={vi.fn()} />);

    await user.click(screen.getByTestId("template-delete-tpl-1"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(stored().map((t) => t.id)).toEqual(["tpl-1", "tpl-2"]);
  });
});
