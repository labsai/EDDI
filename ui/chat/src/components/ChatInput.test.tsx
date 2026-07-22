import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
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
      "too large to send to the model",
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
    expect(screen.getByText(/2 not attached/)).toBeInTheDocument();
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

    expect(
      await screen.findByText(/Delete some attachments first/),
    ).toBeInTheDocument();
  });

  it("announces chip changes to assistive tech", async () => {
    await attach();

    expect(await screen.findByTestId("attachment-chips")).toHaveAttribute(
      "aria-live",
      "polite",
    );
  });
});
