/**
 * Attachments on the operator chat: pick or paste a file, it stages as a chip,
 * uploads to the (lazily-created) conversation, and rides the next send as
 * `attachment_*` context — the same contract as the main chat panel.
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent, createEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorChat, type OperatorChatProps } from "../operator-chat";

beforeEach(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  // jsdom has no object-URL support; image staging creates preview URLs.
  URL.createObjectURL = vi.fn(() => "blob:mock");
  URL.revokeObjectURL = vi.fn();
});

const baseProps: OperatorChatProps = {
  messages: [],
  events: [],
  tracesByMessageId: {},
  isStreaming: false,
  error: null,
  onSend: vi.fn(),
  onStop: vi.fn(),
  onReset: vi.fn(),
  isPaused: false,
  pauseReason: null,
  isResolvingPause: false,
  resolveError: null,
};

function renderChat(overrides: Partial<OperatorChatProps> = {}) {
  return render(
    <MemoryRouter>
      <OperatorChat {...baseProps} {...overrides} />
    </MemoryRouter>,
  );
}

function stubUpload(conversationId: string, storageRef = "op-ref-1") {
  server.use(
    http.post(`*/conversations/${conversationId}/attachments`, () =>
      HttpResponse.json(
        {
          storageRef,
          fileName: "notes.txt",
          mimeType: "text/plain",
          sizeBytes: 9,
          conversationId,
          forwardableInline: true,
        },
        { status: 201 },
      ),
    ),
  );
}

describe("OperatorChat — attachments", () => {
  it("renders no attach affordance when the surface provides no conversation support", () => {
    renderChat();
    expect(screen.queryByTestId("operator-attach-btn")).not.toBeInTheDocument();
  });

  it("stages a picked file and forwards it with the sent message", async () => {
    const user = userEvent.setup();
    const onSend = vi.fn();
    stubUpload("conv-op");
    renderChat({ onSend, conversationId: "conv-op" });

    const file = new File(["some text"], "notes.txt", { type: "text/plain" });
    await user.upload(screen.getByTestId("operator-file-input"), file);
    await screen.findByTestId("attachment-chip");

    await user.type(screen.getByTestId("operator-input"), "please review");
    await user.click(screen.getByTestId("operator-send"));

    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    const [text, attachments] = onSend.mock.calls[0]!;
    expect(text).toBe("please review");
    expect(attachments).toEqual([
      expect.objectContaining({ storageRef: "op-ref-1", fileName: "notes.txt" }),
    ]);
    // The staging area drained on send.
    expect(screen.queryByTestId("attachment-chip")).not.toBeInTheDocument();
  });

  it("lazily creates the conversation when a file is attached before the first message", async () => {
    const user = userEvent.setup();
    const onEnsureConversation = vi.fn().mockResolvedValue("conv-created");
    stubUpload("conv-created");
    renderChat({ conversationId: null, onEnsureConversation });

    const file = new File(["x"], "notes.txt", { type: "text/plain" });
    await user.upload(screen.getByTestId("operator-file-input"), file);

    await screen.findByTestId("attachment-chip");
    expect(onEnsureConversation).toHaveBeenCalledTimes(1);
  });

  it("pasting a file stages it as an attachment", async () => {
    stubUpload("conv-op");
    renderChat({ conversationId: "conv-op" });

    const file = new File(["png-bytes"], "screenshot.png", { type: "image/png" });
    const input = screen.getByTestId("operator-input");
    // jsdom's ClipboardEvent has no settable clipboardData — define it on the
    // created event so React's synthetic event sees the pasted file list.
    const pasteEvent = createEvent.paste(input);
    Object.defineProperty(pasteEvent, "clipboardData", {
      value: { files: [file], types: ["Files"], getData: () => "" },
    });
    fireEvent(input, pasteEvent);

    expect(await screen.findByTestId("attachment-chip")).toBeInTheDocument();
  });

  it("allows an attachment-only send once the upload is ready", async () => {
    const user = userEvent.setup();
    const onSend = vi.fn();
    stubUpload("conv-op");
    renderChat({ onSend, conversationId: "conv-op" });

    const sendButton = screen.getByTestId("operator-send");
    expect(sendButton).toBeDisabled();

    await user.upload(
      screen.getByTestId("operator-file-input"),
      new File(["x"], "notes.txt", { type: "text/plain" }),
    );
    await screen.findByTestId("attachment-chip");

    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);
    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    const [text, attachments] = onSend.mock.calls[0]!;
    expect(text).toBe("");
    expect(attachments).toHaveLength(1);
  });
});
