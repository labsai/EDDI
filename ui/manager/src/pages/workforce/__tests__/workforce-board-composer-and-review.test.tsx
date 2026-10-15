import { describe, it, expect, afterEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { render } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { createTestQueryClient, renderPage, userEvent } from "@/test/test-utils";
import { act } from "@testing-library/react";
import { server } from "@/test/mocks/server";
import { useGroupStreamStore } from "@/hooks/use-group-discussion-stream";
import { WorkforceBoard } from "../workforce-board";

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

afterEach(() => useGroupStreamStore.getState().resetStream("grp1"));

describe("WorkforceBoard — composer and the approval hand-off", () => {
  it("keeps the question when the start is refused, and never toasts 'streaming live' for it", async () => {
    const info = vi_spy(toast, "info");
    const success = vi_spy(toast, "success");
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () =>
        new HttpResponse("question is required", { status: 400, headers: { "Content-Type": "text/plain" } }),
      ),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    const box = await screen.findByRole("textbox");
    await userEvent.type(box, "Ship it?");
    await userEvent.click(screen.getByTestId("board-send"));

    const errorScreen = await screen.findByTestId("board-start-error");
    expect(within(errorScreen).getByText(/question is required/)).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("board-error-start-over"));

    // The composer is back with what was typed, not an empty box.
    await waitFor(() => expect(screen.getByRole("textbox")).toHaveValue("Ship it?"));
    expect(info).not.toHaveBeenCalledWith(expect.stringMatching(/streaming live/i));
    expect(success).not.toHaveBeenCalledWith(expect.stringMatching(/streaming live/i));
  });

  it("hands the attached files back with the question after a refused start", async () => {
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () =>
        new HttpResponse("nope", { status: 400, headers: { "Content-Type": "text/plain" } }),
      ),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    const fileInput = await waitFor(() => {
      const el = document.querySelector<HTMLInputElement>('input[type="file"]');
      expect(el).not.toBeNull();
      return el!;
    });
    await userEvent.upload(fileInput, new File(["hello"], "brief.txt", { type: "text/plain" }));
    await waitFor(() => expect(screen.getByTestId("board-attachments")).toBeInTheDocument());
    await userEvent.type(screen.getByRole("textbox"), "Ship it?");
    await userEvent.click(screen.getByTestId("board-send"));

    await screen.findByTestId("board-start-error");
    await userEvent.click(screen.getByTestId("board-error-start-over"));

    await waitFor(() => expect(screen.getByRole("textbox")).toHaveValue("Ship it?"));
    expect(within(screen.getByTestId("board-attachments")).getByText("brief.txt")).toBeInTheDocument();
  });

  it("does not carry a refused draft into another board", async () => {
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () =>
        new HttpResponse("nope", { status: 400, headers: { "Content-Type": "text/plain" } }),
      ),
    );
    const nav: { go?: NavigateFunction } = {};
    function Handle() {
      nav.go = useNavigate();
      return null;
    }
    render(
      <MemoryRouter initialEntries={["/workforce/grp1?version=1"]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Handle />
            <Routes>
              <Route path="/workforce/:boardId" element={<WorkforceBoard />} />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await userEvent.type(await screen.findByRole("textbox"), "Board A question");
    await userEvent.click(screen.getByTestId("board-send"));
    await screen.findByTestId("board-start-error");

    act(() => void nav.go!("/workforce/grp2?version=1"));
    await waitFor(() => expect(screen.queryByTestId("board-start-error")).not.toBeInTheDocument());
    expect(await screen.findByRole("textbox")).toHaveValue("");
  });

  it("'Review it' opens the PAUSED discussion in the Manager, not the newest one", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json({
          id: String(params.gcId),
          groupId: "grp1",
          userId: "admin",
          state: "AWAITING_APPROVAL",
          originalQuestion: "Ship it?",
          transcript: [],
          memberConversationIds: {},
          currentPhaseIndex: 0,
          currentPhaseName: "Opinion",
          depth: 0,
          taskList: null,
          dynamicMembers: [],
          createdAgentIds: [],
          availableActions: [],
          created: new Date().toISOString(),
          lastModified: new Date().toISOString(),
        }),
      ),
    );
    renderPage("/workforce/grp1?version=2&conversation=gc-paused", <WorkforceBoard />, "/workforce/:boardId");

    const banner = await screen.findByTestId("board-awaiting-approval-banner");
    const href = within(banner).getByRole("link").getAttribute("href")!;
    const url = new URL(href, "http://x");
    expect(url.pathname).toBe("/manage/groups/grp1");
    expect(url.searchParams.get("conversation")).toBe("gc-paused");
    expect(url.searchParams.get("version")).toBe("2");
  });
});

import { vi } from "vitest";
function vi_spy(obj: typeof toast, method: "info" | "success") {
  return vi.spyOn(obj, method);
}
