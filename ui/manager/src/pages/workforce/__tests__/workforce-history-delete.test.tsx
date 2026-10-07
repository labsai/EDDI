import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceHistory } from "../workforce-history";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

describe("WorkforceHistory — deleting a conversation", () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear();
  });

  // Regression: a refused delete had no onError, so the dialog went idle and
  // the row stayed with no word of why.
  it("shows the backend's reason when the delete is refused", async () => {
    const now = new Date().toISOString();
    server.use(
      http.get("*/groups/:groupId/conversations", () =>
        HttpResponse.json([
          {
            id: "gc-1",
            groupId: "board1",
            userId: "me",
            state: "IN_PROGRESS",
            originalQuestion: "Still running",
            created: now,
            lastModified: now,
          },
        ]),
      ),
      http.delete("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json({ error: "Discussion is still running" }, { status: 409 }),
      ),
    );
    renderPage("/workforce/board1/history", <WorkforceHistory />, "/workforce/:boardId/history");
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("history-delete-gc-1"));
    await user.click(await screen.findByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1));
    const [, options] = vi.mocked(toast.error).mock.calls[0]!;
    expect((options as { description?: string }).description).toContain("Discussion is still running");
  });
});
