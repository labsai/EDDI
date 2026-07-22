import { describe, it, expect, vi, beforeAll } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AttachmentsSection } from "@/components/conversations/attachments-section";

beforeAll(() => {
  // jsdom doesn't implement object URLs; the download path uses them.
  Object.defineProperty(URL, "createObjectURL", {
    value: vi.fn(() => "blob:mock"),
    writable: true,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    value: vi.fn(),
    writable: true,
  });
});

describe("AttachmentsSection", () => {
  it("lists the attachments a conversation owns", async () => {
    renderWithProviders(<AttachmentsSection conversationId="conv-1" />);
    await waitFor(() => {
      expect(screen.getByText("document.pdf")).toBeInTheDocument();
    });
    expect(screen.getByText(/application\/pdf/)).toBeInTheDocument();
    expect(screen.getByText(/100 KB/)).toBeInTheDocument();
    expect(screen.getByTestId("attachment-row")).toBeInTheDocument();
  });

  it("shows an empty state when there are no attachments", async () => {
    server.use(
      http.get("*/conversations/:conversationId/attachments", () =>
        HttpResponse.json([])
      )
    );
    renderWithProviders(<AttachmentsSection conversationId="conv-empty" />);
    await waitFor(() => {
      expect(
        screen.getByText("This conversation has no attachments.")
      ).toBeInTheDocument();
    });
    expect(screen.queryByTestId("attachments-delete-all")).not.toBeInTheDocument();
  });

  it("shows an error state when the list fails to load", async () => {
    server.use(
      http.get("*/conversations/:conversationId/attachments", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 })
      )
    );
    renderWithProviders(<AttachmentsSection conversationId="conv-err" />);
    await waitFor(() => {
      expect(screen.getByTestId("attachments-error")).toBeInTheDocument();
    });
  });

  it("deletes a single attachment only after confirmation", async () => {
    let deletedRef = "";
    server.use(
      http.delete(
        "*/conversations/:conversationId/attachments/:storageRef",
        ({ params }) => {
          deletedRef = params.storageRef as string;
          return HttpResponse.json({ deleted: true });
        }
      )
    );
    renderWithProviders(<AttachmentsSection conversationId="conv-1" />);
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByText("document.pdf")).toBeInTheDocument();
    });
    await user.click(screen.getByLabelText("Delete attachment"));
    // confirm dialog first — not deleted yet
    expect(deletedRef).toBe("");
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => {
      expect(deletedRef).toBe("attachment-1");
    });
  });

  it("deletes all attachments after confirmation", async () => {
    let deletedAll = false;
    server.use(
      http.delete("*/conversations/:conversationId/attachments", () => {
        deletedAll = true;
        return HttpResponse.json({ deletedCount: 1 });
      })
    );
    renderWithProviders(<AttachmentsSection conversationId="conv-1" />);
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("attachments-delete-all")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("attachments-delete-all"));
    expect(deletedAll).toBe(false);
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => {
      expect(deletedAll).toBe(true);
    });
  });

  it("downloads an attachment's bytes", async () => {
    let downloaded = false;
    server.use(
      http.get(
        "*/conversations/:conversationId/attachments/:storageRef",
        () => {
          downloaded = true;
          return HttpResponse.text("bytes");
        }
      )
    );
    renderWithProviders(<AttachmentsSection conversationId="conv-1" />);
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByText("document.pdf")).toBeInTheDocument();
    });
    await user.click(screen.getByLabelText("Download"));
    await waitFor(() => {
      expect(downloaded).toBe(true);
    });
  });
});
