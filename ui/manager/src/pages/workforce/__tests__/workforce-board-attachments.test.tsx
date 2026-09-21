import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceBoard } from "../workforce-board";

/**
 * The board's composer stages files; this pins that they reach the wire.
 *
 * The bug this guards was invisible to the compiler: `BoardInput` called
 * `onSend(message, attachment)` while the board declared
 * `handleSend(question: string)`, and TypeScript accepts a handler that takes
 * fewer parameters. The file was staged, chipped, and dropped on send with no
 * error. Only a test that reads the request body can see that.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

describe("WorkforceBoard — attachments reach the discussion endpoint", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.removeItem("workforce-board-config-panel");
  });

  it("posts staged files with the question that starts a discussion", async () => {
    let body: { question?: string; attachments?: { fileName?: string; data?: string }[] } | null =
      null;
    server.use(
      http.post("*/groups/:groupId/conversations/stream", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return new HttpResponse("event: group_error\ndata: {}\n\n", {
          headers: { "Content-Type": "text/event-stream" },
        });
      }),
    );

    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");

    const fileInput = await waitFor(() => {
      const el = document.querySelector<HTMLInputElement>('input[type="file"]');
      expect(el).not.toBeNull();
      return el!;
    });
    await userEvent.upload(fileInput, new File(["hello"], "brief.txt", { type: "text/plain" }));
    await waitFor(() => expect(screen.getByTestId("board-attachments")).toBeInTheDocument());

    await userEvent.type(screen.getByRole("textbox"), "Review this brief");
    await userEvent.click(screen.getByTestId("board-send"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.question).toBe("Review this brief");
    expect(body!.attachments).toHaveLength(1);
    expect(body!.attachments![0]!.fileName).toBe("brief.txt");
    expect(body!.attachments![0]!.data).toBeTruthy();
  });
});
