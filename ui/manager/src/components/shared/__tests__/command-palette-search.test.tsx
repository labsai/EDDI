import { describe, it, expect, vi, beforeEach, beforeAll, afterAll } from "vitest";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { CommandPalette } from "../command-palette";
import { useCommandPalette } from "@/hooks/use-command-palette";

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => mockNavigate };
});

beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  Element.prototype.scrollIntoView = vi.fn();
});

afterAll(() => {
  // @ts-expect-error restore
  delete global.ResizeObserver;
});

beforeEach(() => {
  mockNavigate.mockClear();
  useCommandPalette.setState({ isOpen: false, recentPages: [] });
});

function descriptor(id: string, name: string, version = 1) {
  return {
    resource: `eddi://ai.labs.agent/agentstore/agents/${id}?version=${version}`,
    name,
    description: "",
    createdOn: 0,
    lastModifiedOn: 0,
  };
}

describe("command palette — pages", () => {
  it("lists the pages the sidebar has, including those it used to omit", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);

    for (const label of [
      "Platform Operator",
      "Approvals",
      "Secrets",
      "Schedules",
      "Channels",
      "Capabilities",
      "Triggers",
      "Workspaces",
      "Linked accounts",
    ]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
  });

  it("Create New Agent opens the wizard — the old ?action=create target was read by nothing", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);

    fireEvent.click(screen.getByText("Create New Agent"));
    expect(mockNavigate).toHaveBeenCalledWith("/manage/agents/wizard");
  });
});

describe("command palette — agents", () => {
  it("shows one row per agent even when the listing carries several versions", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([descriptor("a1", "Support Bot", 3), descriptor("a1", "Support Bot", 2)]),
      ),
    );
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);

    await screen.findByText("Support Bot");
    expect(screen.getAllByText("Support Bot")).toHaveLength(1);
  });

  it("asks the server to search rather than filtering a single page in the browser", async () => {
    const seen: Array<{ filter: string | null; limit: string | null }> = [];
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const url = new URL(request.url);
        seen.push({ filter: url.searchParams.get("filter"), limit: url.searchParams.get("limit") });
        const filter = url.searchParams.get("filter");
        return HttpResponse.json(filter === "zeta" ? [descriptor("z9", "Zeta Agent")] : [descriptor("a1", "Alpha")]);
      }),
    );
    useCommandPalette.setState({ isOpen: true });
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await screen.findByText("Alpha");
    await user.type(screen.getByRole("combobox"), "zeta");

    expect(await screen.findByText("Zeta Agent")).toBeInTheDocument();
    // The initial load and the search both went to the server; the search carries
    // the typed text, and the page is larger than the old ten-row slice.
    expect(seen.some((s) => s.filter === "zeta")).toBe(true);
    expect(Number(seen[0]!.limit)).toBeGreaterThan(10);
  });

  it("does not fetch agents while closed", async () => {
    let calls = 0;
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        calls += 1;
        return HttpResponse.json([]);
      }),
    );
    renderWithProviders(<CommandPalette />);
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
  });
});

describe("command palette — dialog behaviour", () => {
  it("is a modal dialog", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    const dialog = screen.getByRole("dialog");
    expect(dialog).toBeInTheDocument();
  });

  it("returns focus to the opener when it closes", async () => {
    const user = userEvent.setup();
    renderWithProviders(<button data-testid="opener">open</button>);
    const opener = screen.getByTestId("opener");
    opener.focus();

    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    await screen.findByRole("dialog");

    await user.keyboard("{Escape}");
    await waitFor(() => expect(useCommandPalette.getState().isOpen).toBe(false));
    await waitFor(() => expect(opener).toHaveFocus());
  });

  it("leaves Ctrl+K alone inside a Monaco editor", () => {
    renderWithProviders(<CommandPalette />);
    const editor = document.createElement("div");
    editor.className = "monaco-editor";
    const input = document.createElement("textarea");
    editor.appendChild(input);
    document.body.appendChild(editor);
    try {
      const event = new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true, cancelable: true });
      input.dispatchEvent(event);
      expect(useCommandPalette.getState().isOpen).toBe(false);
      expect(event.defaultPrevented).toBe(false);
    } finally {
      editor.remove();
    }
  });
});
