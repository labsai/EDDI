import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
  within,
} from "@testing-library/react";
import { ChatInput } from "./ChatInput";
import { ChatProvider, useChatState } from "@/store/chat-store";
import {
  uploadAttachment,
  deleteAttachment,
  MAX_ATTACHMENTS_PER_TURN,
} from "@/api/attachments-api";
import { ApiError } from "@/api/http";

/**
 * ChatInput reports upload problems by posting into the transcript, which the
 * widget renders and the composer does not — so mirror the messages here to
 * assert on what the user would actually be told.
 */
function Transcript() {
  const { messages } = useChatState();
  return (
    <div data-testid="transcript">
      {messages.map((m) => (
        <p key={m.id}>{m.content}</p>
      ))}
    </div>
  );
}

function renderInput(props = {}) {
  const onSend = vi.fn();
  const result = render(
    <ChatProvider>
      <ChatInput onSend={onSend} {...props} />
      <Transcript />
    </ChatProvider>,
  );
  return { ...result, onSend };
}

describe("ChatInput", () => {
  it("renders a textarea and send button", () => {
    renderInput();
    expect(screen.getByTestId("chat-input")).toBeInTheDocument();
    expect(screen.getByTestId("chat-send")).toBeInTheDocument();
  });

  it("calls onSend on Enter key", () => {
    const { onSend } = renderInput();
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).toHaveBeenCalledWith("Hello", false);
  });

  it("does NOT send on Shift+Enter", () => {
    const { onSend } = renderInput();
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: true });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("does NOT send when input is empty", () => {
    const { onSend } = renderInput();
    const textarea = screen.getByTestId("chat-input");
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("does NOT send when disabled", () => {
    const { onSend } = renderInput({ disabled: true });
    const textarea = screen.getByTestId("chat-input");
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("clears input after send", () => {
    renderInput();
    const textarea = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "Hello" } });
    fireEvent.keyDown(textarea, { key: "Enter", shiftKey: false });
    expect(textarea.value).toBe("");
  });
});

/* ─── Attachments ───────────────────────────── */

vi.mock("@/api/attachments-api", async () => {
  const actual = await vi.importActual<typeof import("@/api/attachments-api")>(
    "@/api/attachments-api",
  );
  return {
    ...actual,
    uploadAttachment: vi.fn(async (_convId: string, file: File) => ({
      storageRef: `ref-${file.name}`,
      fileName: file.name,
      mimeType: "application/pdf",
      sizeBytes: 3,
      forwardableInline: true,
    })),
    deleteAttachment: vi.fn(async () => {}),
  };
});

const pdf = (name: string) =>
  new File(["abc"], name, { type: "application/pdf" });

describe("ChatInput — attachments", () => {
  beforeEach(() => {
    vi.mocked(deleteAttachment).mockClear();
  });

  async function attach(fileName = "invoice.pdf") {
    const { onSend } = renderInput({ conversationId: "conv-1" });
    const input = screen.getByTestId("chat-file-input");
    fireEvent.change(input, { target: { files: [pdf(fileName)] } });
    return { onSend };
  }

  it("stages the upload instead of sending it as a message", async () => {
    // The ref used to be stitched into the message text as "📎 name [ref:…]",
    // which the backend never reads — the file silently never reached the model.
    const { onSend } = await attach();

    await screen.findByTestId("attachment-chip");
    expect(onSend).not.toHaveBeenCalled();
  });

  it("shows the staged file name as a removable chip", async () => {
    await attach("invoice.pdf");

    const chip = await screen.findByTestId("attachment-chip");
    expect(chip).toHaveTextContent("invoice.pdf");
    expect(screen.getByTestId("attachment-remove")).toBeInTheDocument();
  });

  it("removes a staged attachment when its remove button is clicked", async () => {
    await attach();
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("attachment-remove"));

    expect(screen.queryByTestId("attachment-chip")).not.toBeInTheDocument();
  });

  it("allows sending with only an attachment and no typed text", async () => {
    const { onSend } = await attach();
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("chat-send"));

    expect(onSend).toHaveBeenCalled();
  });

  it("deletes the blob server-side when a staged chip is removed", async () => {
    // Removing the chip client-side only left the file in the store for the
    // life of the conversation, still counting against the per-conversation
    // file and byte quotas until no further attachment could be accepted.
    await attach("invoice.pdf");
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("attachment-remove"));

    expect(vi.mocked(deleteAttachment)).toHaveBeenCalledWith(
      "conv-1",
      "ref-invoice.pdf",
    );
  });

  it("keeps removing the chip even when the server-side delete fails", async () => {
    vi.mocked(deleteAttachment).mockRejectedValueOnce(new Error("offline"));
    await attach();
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("attachment-remove"));

    expect(screen.queryByTestId("attachment-chip")).not.toBeInTheDocument();
  });

  it("warns on a file that was stored but is too large to send to the model", async () => {
    // The backend accepts up to max-size-bytes but only inlines up to
    // max-forward-bytes. A file in between is dropped at forward time and the
    // skip is recorded setPublic(false), so the upload response is the only
    // chance we get to tell the user.
    vi.mocked(uploadAttachment).mockResolvedValueOnce({
      storageRef: "ref-big",
      fileName: "huge.pdf",
      mimeType: "application/pdf",
      sizeBytes: 14_000_000,
      forwardableInline: false,
    });
    await attach("huge.pdf");

    expect(await screen.findByTestId("attachment-warn")).toHaveTextContent(
      "too large to send directly",
    );
    expect(await screen.findByTestId("attachment-chip")).toHaveClass(
      "chat-attachments__chip--warn",
    );
  });

  it("does NOT warn when the backend omits forwardableInline", async () => {
    // An older backend simply does not send the field. Absence is not a denial.
    vi.mocked(uploadAttachment).mockResolvedValueOnce({
      storageRef: "ref-x",
      fileName: "x.pdf",
      mimeType: "application/pdf",
      sizeBytes: 3,
    });
    await attach("x.pdf");
    await screen.findByTestId("attachment-chip");

    expect(screen.queryByTestId("attachment-warn")).not.toBeInTheDocument();
  });

  it("marks the file input as multi-select", async () => {
    // Asserted directly because jsdom does not enforce `multiple`:
    // fireEvent.change can hand a component several files whether or not the
    // attribute is present, so the batch tests below would pass without it.
    // This is the only assertion that pins the attribute that makes the real
    // OS picker allow more than one file.
    renderInput({ conversationId: "conv-1" });

    expect(screen.getByTestId("chat-file-input")).toHaveAttribute("multiple");
  });

  it("uploads every file from a single multi-file pick", async () => {
    renderInput({ conversationId: "conv-1" });
    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: [pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf")] },
    });

    await waitFor(() =>
      expect(screen.getAllByTestId("attachment-chip")).toHaveLength(3),
    );
  });

  it("caps a multi-file pick at the per-turn limit and says so", async () => {
    // The backend drops the excess server-side, so accepting all seven here
    // would tell the user files were sent that never reached the agent.
    renderInput({ conversationId: "conv-1" });
    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: Array.from({ length: 7 }, (_, i) => pdf(`f${i}.pdf`)) },
    });

    await waitFor(() =>
      expect(screen.getAllByTestId("attachment-chip")).toHaveLength(
        MAX_ATTACHMENTS_PER_TURN,
      ),
    );
    // Names them: slice() keeps OS-determined FileList order, so a bare count
    // would leave the user guessing which two were dropped.
    expect(
      within(screen.getByTestId("transcript")).getByText(
        /Not attached: f5\.pdf, f6\.pdf/,
      ),
    ).toBeInTheDocument();
  });

  it("shows a placeholder chip while the upload is still in flight", async () => {
    let release: (v: unknown) => void = () => {};
    vi.mocked(uploadAttachment).mockImplementationOnce(
      () => new Promise((res) => { release = res; }) as never,
    );
    renderInput({ conversationId: "conv-1" });
    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: [pdf("slow.pdf")] },
    });

    expect(
      await screen.findByTestId("attachment-chip-uploading"),
    ).toHaveTextContent("slow.pdf");

    await act(async () => {
      release({
        storageRef: "ref-slow",
        fileName: "slow.pdf",
        mimeType: "application/pdf",
        sizeBytes: 3,
        forwardableInline: true,
      });
    });
    expect(
      screen.queryByTestId("attachment-chip-uploading"),
    ).not.toBeInTheDocument();
  });

  it("surfaces the server's own reason for a rejection", async () => {
    // ATTACHMENT_REJECTED is a catch-all — a quota rejection used to be
    // reported to the user as an unaccepted file type.
    vi.mocked(uploadAttachment).mockRejectedValueOnce(
      new ApiError(
        400,
        JSON.stringify({
          error: "Conversation attachment limit reached (50). Delete some attachments first.",
          code: "ATTACHMENT_REJECTED",
        }),
        "Attachment upload failed",
      ),
    );
    await attach("late.pdf");

    await waitFor(() =>
      expect(
        within(screen.getByTestId("transcript")).getByText(
          /Delete some attachments first/,
        ),
      ).toBeInTheDocument(),
    );
  });

  it.each([
    ["a bare 413 with no JSON envelope", new ApiError(413, "<html>413</html>", "x"), /too large to upload/],
    ["a 400 ATTACHMENT_TOO_LARGE", new ApiError(400, JSON.stringify({ error: "File too large: 9", code: "ATTACHMENT_TOO_LARGE" }), "x"), /too large to upload/],
    ["a 403", new ApiError(403, "", "x"), /not allowed to attach/],
    ["a 401", new ApiError(401, "", "x"), /not allowed to attach/],
    ["a non-ApiError", new Error("socket hang up"), /Failed to upload/],
  ])("explains %s", async (_label, err, expected) => {
    vi.mocked(uploadAttachment).mockRejectedValueOnce(err);
    await attach("doc.pdf");

    await waitFor(() =>
      expect(
        within(screen.getByTestId("transcript")).getByText(expected),
      ).toBeInTheDocument(),
    );
  });

  it("refuses a further pick once the cap is already full", async () => {
    // The batch-overflow path and the already-full path are different branches.
    renderInput({ conversationId: "conv-1" });
    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: Array.from({ length: 5 }, (_, i) => pdf(`f${i}.pdf`)) },
    });
    await waitFor(() =>
      expect(screen.getAllByTestId("attachment-chip")).toHaveLength(5),
    );

    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: [pdf("one-too-many.pdf")] },
    });

    await waitFor(() =>
      expect(
        within(screen.getByTestId("transcript")).getByText(
          /You can attach at most 5 files per message\.$/,
        ),
      ).toBeInTheDocument(),
    );
    expect(screen.getAllByTestId("attachment-chip")).toHaveLength(5);
  });

  it("keeps the live region mounted before anything is attached", async () => {
    // A live region that appears together with its content is not announced,
    // so this one must already exist while the composer is empty.
    renderInput({ conversationId: "conv-1" });

    expect(screen.getByTestId("chat-live")).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByTestId("attachment-chips")).not.toBeInTheDocument();
  });

  it("announces an attachment and its removal", async () => {
    await attach("invoice.pdf");
    await screen.findByTestId("attachment-chip");
    expect(screen.getByTestId("chat-live")).toHaveTextContent("invoice.pdf attached.");

    fireEvent.click(screen.getByTestId("attachment-remove"));

    expect(screen.getByTestId("chat-live")).toHaveTextContent("invoice.pdf removed.");
  });

  it("announces an upload failure, which only ever REMOVES a chip", async () => {
    // The default aria-relevant does not cover removals, so a failure had no
    // other route to a screen reader.
    vi.mocked(uploadAttachment).mockRejectedValueOnce(
      new ApiError(400, JSON.stringify({ error: "Bad type", code: "ATTACHMENT_REJECTED" }), "x"),
    );
    await attach("virus.exe");

    await waitFor(() =>
      expect(screen.getByTestId("chat-live")).toHaveTextContent("virus.exe was rejected"),
    );
    expect(screen.queryByTestId("attachment-chip-uploading")).not.toBeInTheDocument();
  });

  it("refuses to send while an upload is still in flight", async () => {
    // Sending here would go out with NO attachment context, then CLEAR_ATTACHMENTS
    // would fire and the upload would land as a chip belonging to the NEXT turn —
    // leaving the user looking at a file they believe they already sent.
    let release: (v: unknown) => void = () => {};
    vi.mocked(uploadAttachment).mockImplementationOnce(
      () => new Promise((res) => { release = res; }) as never,
    );
    const { onSend } = renderInput({ conversationId: "conv-1" });
    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: [pdf("slow.pdf")] },
    });
    await screen.findByTestId("attachment-chip-uploading");

    const input = screen.getByTestId("chat-input");
    fireEvent.change(input, { target: { value: "here you go" } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });

    expect(onSend).not.toHaveBeenCalled();
    expect(screen.getByTestId("chat-send")).toBeDisabled();

    await act(async () => {
      release({
        storageRef: "ref-slow", fileName: "slow.pdf",
        mimeType: "application/pdf", sizeBytes: 3, forwardableInline: true,
      });
    });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
    expect(onSend).toHaveBeenCalled();
  });

  it("deletes against the conversation the upload reported, not the current one", async () => {
    // An upload started before New Conversation lands afterwards and stages
    // into the fresh composer. Deleting it against the new id is refused by the
    // store's owner check, and the original blob leaks against the very quota
    // this is meant to protect.
    vi.mocked(uploadAttachment).mockResolvedValueOnce({
      storageRef: "ref-old",
      fileName: "old.pdf",
      mimeType: "application/pdf",
      sizeBytes: 3,
      conversationId: "conv-OLD",
    });
    renderInput({ conversationId: "conv-NEW" });
    fireEvent.change(screen.getByTestId("chat-file-input"), {
      target: { files: [pdf("old.pdf")] },
    });
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("attachment-remove"));

    expect(vi.mocked(deleteAttachment)).toHaveBeenCalledWith("conv-OLD", "ref-old");
  });

  it("moves focus to the attach button when the last chip is removed", async () => {
    await attach();
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("attachment-remove"));

    await waitFor(() =>
      expect(screen.getByTestId("chat-attach-btn")).toHaveFocus(),
    );
  });
});
