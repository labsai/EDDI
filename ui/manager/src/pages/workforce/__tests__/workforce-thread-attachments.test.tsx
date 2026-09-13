/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";

import * as useGroupsHook from "@/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@/lib/api/chat";
import * as attachmentsApi from "@/lib/api/attachments";

import { WorkforceThread } from "../workforce-thread";

// ─── Polyfills ─────────────────────────────────────────────────────

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

// JSDOM doesn't support createObjectURL — always return a fake blob URL
URL.createObjectURL = () => "blob:http://localhost/mock-preview";

// ─── Shared Helpers ────────────────────────────────────────────────

function setupMocks(overrides?: {
  conversationId?: string;
  member?: { agentId: string; displayName: string; role?: string };
  startConversation?: ReturnType<typeof vi.fn>;
  sendMessageStreaming?: ReturnType<typeof vi.fn>;
  readConversation?: ReturnType<typeof vi.fn>;
}) {
  const convId = overrides?.conversationId ?? "conv-test-123";
  const member = overrides?.member ?? {
    agentId: "agent1",
    displayName: "Test Agent",
  };

  vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
    getThread: () => null,
    registerThread: vi.fn(),
    updateActivity: vi.fn(),
  } as any);

  vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
    data: {
      id: "board1",
      name: "Board",
      members: [member],
    },
  } as any);

  const startConv =
    overrides?.startConversation ??
    vi.spyOn(chatApi, "startConversation").mockResolvedValue(convId);
  const readConv =
    overrides?.readConversation ??
    vi
      .spyOn(chatApi, "readConversation")
      .mockResolvedValue({ conversationSteps: [] } as any);
  // One entry point now: the thread streams its turns, so text arrives as it
  // is generated instead of appearing whole after the round trip.
  const sendStream =
    overrides?.sendMessageStreaming ??
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
      () => streamOf("Agent reply"),
    );

  return { convId, member, startConv, readConv, sendStream };
}

/** An SSE generator yielding `text` as one token, then `done`. */
async function* streamOf(text: string) {
  yield { type: "token" as const, data: text };
  yield { type: "done" as const, data: "" };
}

function renderThread(memberId = "agent1") {
  return renderPage(
    `/workforce/board1/thread/${memberId}`,
    <WorkforceThread />,
    "/workforce/:boardId/thread/:memberId",
  );
}

async function waitForInit() {
  await waitFor(() => {
    expect(screen.getAllByText(/Test Agent/i).length).toBeGreaterThan(0);
  });
  // Also wait for conversationId to be set (textarea becomes enabled).
  // Without this, pickFile may fire before handleFileChange has a valid
  // conversationId, silently returning without uploading — a race that only
  // surfaces under CI CPU saturation.
  await waitFor(() => {
    const textarea = screen.getByRole("textbox");
    expect(textarea).not.toBeDisabled();
  });
}

function createMockFile(
  name: string,
  sizeBytes: number,
  type: string,
): File {
  const buffer = new ArrayBuffer(Math.min(sizeBytes, 64));
  const file = new File([buffer], name, { type });
  Object.defineProperty(file, "size", { value: sizeBytes });
  return file;
}

/** Simulate selecting a file in the hidden input via fireEvent */
function pickFile(file: File) {
  const fileInput = document.querySelector(
    'input[type="file"]',
  ) as HTMLInputElement;
  // fireEvent.change properly dispatches through React's synthetic event system
  fireEvent.change(fileInput, {
    target: { files: [file] },
  });
}

// ─── Tests ─────────────────────────────────────────────────────────

describe("WorkforceThread – Attachment Features", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("ThreadInput attachment UI", () => {
    it("renders attach button and hidden file input", async () => {
      setupMocks();
      renderThread();
      await waitForInit();

      const attachBtn = screen.getByRole("button", {
        name: /Attach file/i,
      });
      expect(attachBtn).toBeInTheDocument();

      // Hidden file input uses className="hidden"
      const fileInput = document.querySelector(
        'input[type="file"]',
      ) as HTMLInputElement;
      expect(fileInput).toBeTruthy();
      expect(fileInput.classList.contains("hidden")).toBe(true);
    });

    it("rejects files exceeding size limit", async () => {
      setupMocks();
      const uploadSpy = vi
        .spyOn(attachmentsApi, "uploadAttachment")
        .mockResolvedValue({} as any);
      renderThread();
      await waitForInit();

      const largeFile = createMockFile(
        "large.pdf",
        25 * 1024 * 1024,
        "application/pdf",
      );
      pickFile(largeFile);

      // Upload should NOT be triggered for oversized files
      await waitFor(() => {
        expect(uploadSpy).not.toHaveBeenCalled();
      });
    });

    it("rejects unsupported file types", async () => {
      setupMocks();
      const uploadSpy = vi
        .spyOn(attachmentsApi, "uploadAttachment")
        .mockResolvedValue({} as any);
      renderThread();
      await waitForInit();

      const badFile = createMockFile(
        "script.exe",
        1024,
        "application/x-msdownload",
      );
      pickFile(badFile);

      await waitFor(() => {
        expect(uploadSpy).not.toHaveBeenCalled();
      });
    });

    it("uploads a valid file and shows ready state", async () => {
      setupMocks();
      const uploadSpy = vi
        .spyOn(attachmentsApi, "uploadAttachment")
        .mockResolvedValue({
          storageRef: "ref-123",
          fileName: "doc.pdf",
          mimeType: "application/pdf",
          sizeBytes: 1024,
          forwardableInline: true,
        });

      renderThread();
      await waitForInit();

      const file = createMockFile("doc.pdf", 1024, "application/pdf");
      pickFile(file);

      // Wait for both the upload call AND the rendered chip
      await waitFor(() => {
        expect(uploadSpy).toHaveBeenCalledWith("conv-test-123", file);
        expect(screen.getByText("doc.pdf")).toBeInTheDocument();
      });
    });

    it("uploads an image and shows preview thumbnail", async () => {
      setupMocks();
      vi.spyOn(attachmentsApi, "uploadAttachment").mockResolvedValue({
        storageRef: "ref-img",
        fileName: "photo.jpg",
        mimeType: "image/jpeg",
        sizeBytes: 5000,
        forwardableInline: true,
      });

      renderThread();
      await waitForInit();

      const imgFile = createMockFile("photo.jpg", 5000, "image/jpeg");
      pickFile(imgFile);

      // Image preview should appear (mock blob URL)
      await waitFor(() => {
        const previews = document.querySelectorAll("img");
        const hasPreview = Array.from(previews).some(
          (img) =>
            img.src.startsWith("blob:") || img.alt === "photo.jpg",
        );
        expect(hasPreview).toBe(true);
      });
    });

    it("shows error state when upload fails", async () => {
      setupMocks();
      vi.spyOn(attachmentsApi, "uploadAttachment").mockRejectedValue(
        new Error("Server error"),
      );

      renderThread();
      await waitForInit();

      const file = createMockFile("doc.pdf", 1024, "application/pdf");
      pickFile(file);

      // Error chip with file name should appear
      await waitFor(() => {
        expect(screen.getByText("doc.pdf")).toBeInTheDocument();
      });
    });

    it("removes an attachment with server-side cleanup", async () => {
      setupMocks();
      vi.spyOn(attachmentsApi, "uploadAttachment").mockResolvedValue({
        storageRef: "ref-del",
        fileName: "delete-me.pdf",
        mimeType: "application/pdf",
        sizeBytes: 1024,
        forwardableInline: true,
      });
      const deleteSpy = vi
        .spyOn(attachmentsApi, "deleteAttachment")
        .mockResolvedValue(undefined as any);

      renderThread();
      await waitForInit();

      const file = createMockFile("delete-me.pdf", 1024, "application/pdf");
      pickFile(file);

      // Wait for upload to complete and file chip to appear
      await waitFor(() => {
        expect(screen.getByText("delete-me.pdf")).toBeInTheDocument();
      });

      // Click the remove button (X) on the attachment chip
      const removeButtons = screen.getAllByRole("button");
      const removeBtn = removeButtons.find((btn) => {
        const label = btn.getAttribute("aria-label") ?? "";
        return label.toLowerCase().includes("remove");
      });

      if (removeBtn) {
        const user = userEvent.setup();
        await user.click(removeBtn);

        await waitFor(() => {
          expect(deleteSpy).toHaveBeenCalledWith(
            "conv-test-123",
            "ref-del",
          );
        });
      }
    });
  });

  describe("Sending messages with attachments", () => {
    it("sends message with attachment context", async () => {
      const { sendStream } = setupMocks();
      vi.spyOn(attachmentsApi, "uploadAttachment").mockResolvedValue({
        storageRef: "ref-send",
        fileName: "report.pdf",
        mimeType: "application/pdf",
        sizeBytes: 2048,
        forwardableInline: true,
      });

      renderThread();
      await waitForInit();

      // Attach a file via fireEvent
      const file = createMockFile("report.pdf", 2048, "application/pdf");
      pickFile(file);

      // Wait for upload complete
      await waitFor(() => {
        expect(screen.getByText("report.pdf")).toBeInTheDocument();
      });

      // Type and send
      const user = userEvent.setup();
      const textarea = screen.getByPlaceholderText(/Message Test Agent/i);
      await user.type(textarea, "Here is the report");

      const sendBtn = screen.getByRole("button", { name: /Send/i });
      await user.click(sendBtn);

      await waitFor(() => {
        expect(sendStream).toHaveBeenCalledWith(
          "production",
          "agent1",
          "conv-test-123",
          expect.objectContaining({
            input: "Here is the report",
            context: expect.objectContaining({
              attachment_0: expect.objectContaining({
                type: "object",
                value: expect.objectContaining({
                  storageRef: "ref-send",
                }),
              }),
            }),
          }),
          expect.anything(),
        );
      });
    });

    it("sends attachment-only message (no text)", async () => {
      const { sendStream } = setupMocks();
      vi.spyOn(attachmentsApi, "uploadAttachment").mockResolvedValue({
        storageRef: "ref-only",
        fileName: "image.png",
        mimeType: "image/png",
        sizeBytes: 4096,
        forwardableInline: true,
      });

      renderThread();
      await waitForInit();

      const file = createMockFile("image.png", 4096, "image/png");
      pickFile(file);

      await waitFor(() => {
        expect(screen.getByText("image.png")).toBeInTheDocument();
      });

      // Send without typing
      const user = userEvent.setup();
      const sendBtn = screen.getByRole("button", { name: /Send/i });
      await user.click(sendBtn);

      await waitFor(() => {
        expect(sendStream).toHaveBeenCalledWith(
          "production",
          "agent1",
          "conv-test-123",
          expect.objectContaining({
            input: "image.png",
            context: expect.objectContaining({
              attachment_0: expect.objectContaining({
                type: "object",
                value: expect.objectContaining({
                  storageRef: "ref-only",
                }),
              }),
            }),
          }),
          expect.anything(),
        );
      });
    });

    it("includes non-forwardable attachments in context", async () => {
      const { sendStream } = setupMocks();
      vi.spyOn(attachmentsApi, "uploadAttachment").mockResolvedValue({
        storageRef: "ref-large",
        fileName: "big.pdf",
        mimeType: "application/pdf",
        sizeBytes: 15 * 1024 * 1024,
        forwardableInline: false,
      });

      renderThread();
      await waitForInit();

      const file = createMockFile(
        "big.pdf",
        15 * 1024 * 1024,
        "application/pdf",
      );
      pickFile(file);

      await waitFor(() => {
        expect(screen.getByText("big.pdf")).toBeInTheDocument();
      });

      const user = userEvent.setup();
      const textarea = screen.getByPlaceholderText(/Message Test Agent/i);
      await user.type(textarea, "Big file");

      const sendBtn = screen.getByRole("button", { name: /Send/i });
      await user.click(sendBtn);

      // Non-forwardable should still be in context
      await waitFor(() => {
        expect(sendStream).toHaveBeenCalledWith(
          "production",
          "agent1",
          "conv-test-123",
          expect.objectContaining({
            context: expect.objectContaining({
              attachment_0: expect.objectContaining({
                type: "object",
                value: expect.objectContaining({
                  storageRef: "ref-large",
                }),
              }),
            }),
          }),
          expect.anything(),
        );
      });
    });
  });

  describe("Thread initialization and history", () => {
    it("renders empty state with prompt when no messages", async () => {
      setupMocks();
      renderThread();
      await waitForInit();

      expect(
        screen.getByText(/Chat with Test Agent/i),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/Send a message to begin chatting/i),
      ).toBeInTheDocument();
    });

    it("resumes existing conversation from thread store", async () => {
      const readConvSpy = vi
        .spyOn(chatApi, "readConversation")
        .mockResolvedValue({
          conversationSteps: [
            {
              timestamp: Date.now() - 10000,
              conversationStep: [
                { key: "input:initial", value: "Previous message" },
              ],
            },
            {
              timestamp: Date.now() - 5000,
              conversationStep: [
                { key: "output:text:0", value: "Previous reply" },
              ],
            },
          ],
        } as any);

      vi.spyOn(
        useWorkforceThreadsHook,
        "useWorkforceThreads",
      ).mockReturnValue({
        getThread: () => ({
          conversationId: "existing-conv",
          agentId: "agent1",
          agentName: "Test Agent",
          lastActivity: Date.now(),
        }),
        registerThread: vi.fn(),
        updateActivity: vi.fn(),
      } as any);

      vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
        data: {
          id: "board1",
          name: "Board",
          members: [
            { agentId: "agent1", displayName: "Test Agent" },
          ],
        },
      } as any);

      vi.spyOn(chatApi, "startConversation").mockResolvedValue(
        "existing-conv",
      );

      renderThread();

      await waitFor(() => {
        expect(readConvSpy).toHaveBeenCalledWith(
          "production",
          "agent1",
          "existing-conv",
        );
      });

      await waitFor(() => {
        expect(
          screen.getByText("Previous message"),
        ).toBeInTheDocument();
        expect(
          screen.getByText("Previous reply"),
        ).toBeInTheDocument();
      });
    });

    it("sends a plain message with no context block", async () => {
      const { sendStream } = setupMocks();
      renderThread();
      await waitForInit();

      const user = userEvent.setup();
      const textarea = screen.getByPlaceholderText(/Message Test Agent/i);
      await user.type(textarea, "Plain text message");

      const sendBtn = screen.getByRole("button", { name: /Send/i });
      await user.click(sendBtn);

      await waitFor(() => {
        expect(sendStream).toHaveBeenCalledWith(
          "production",
          "agent1",
          "conv-test-123",
          { input: "Plain text message" },
          expect.anything(),
        );
      });
    });
  });

  describe("UI elements", () => {
    it("textarea has aria-label for accessibility", async () => {
      setupMocks();
      renderThread();
      await waitForInit();

      const textarea = screen.getByPlaceholderText(/Message Test Agent/i);
      expect(textarea).toHaveAttribute("aria-label");
    });

    it("send button is disabled when no content", async () => {
      setupMocks();
      renderThread();
      await waitForInit();

      const sendBtn = screen.getByRole("button", { name: /Send/i });
      expect(sendBtn).toBeDisabled();
    });

    it("send button is enabled when text is entered", async () => {
      setupMocks();
      renderThread();
      await waitForInit();

      const user = userEvent.setup();
      const textarea = screen.getByPlaceholderText(/Message Test Agent/i);
      await user.type(textarea, "Hello");

      const sendBtn = screen.getByRole("button", { name: /Send/i });
      expect(sendBtn).toBeEnabled();
    });

    it("shows member name and back link", async () => {
      setupMocks();
      renderThread();
      await waitForInit();

      const backLink = screen.getByRole("link");
      expect(backLink).toBeInTheDocument();

      expect(
        screen.getAllByText(/Test Agent/i).length,
      ).toBeGreaterThan(0);
    });
  });

  describe("parseConversationSteps edge cases", () => {
    it("handles object output values with text field", async () => {
      vi.spyOn(chatApi, "readConversation").mockResolvedValue({
        conversationSteps: [
          {
            timestamp: Date.now(),
            conversationStep: [
              {
                key: "output:text:0",
                value: { text: "Object text output" },
              },
            ],
          },
        ],
      } as any);

      vi.spyOn(
        useWorkforceThreadsHook,
        "useWorkforceThreads",
      ).mockReturnValue({
        getThread: () => ({
          conversationId: "conv-parse",
          agentId: "agent1",
          agentName: "Test Agent",
          lastActivity: Date.now(),
        }),
        registerThread: vi.fn(),
        updateActivity: vi.fn(),
      } as any);

      vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
        data: {
          id: "board1",
          name: "Board",
          members: [
            { agentId: "agent1", displayName: "Test Agent" },
          ],
        },
      } as any);

      vi.spyOn(chatApi, "startConversation").mockResolvedValue(
        "conv-parse",
      );

      renderThread();

      await waitFor(() => {
        expect(
          screen.getByText("Object text output"),
        ).toBeInTheDocument();
      });
    });

    it("handles object output values with input field", async () => {
      vi.spyOn(chatApi, "readConversation").mockResolvedValue({
        conversationSteps: [
          {
            timestamp: Date.now(),
            conversationStep: [
              {
                key: "output:text:0",
                value: { input: "Input field output" },
              },
            ],
          },
        ],
      } as any);

      vi.spyOn(
        useWorkforceThreadsHook,
        "useWorkforceThreads",
      ).mockReturnValue({
        getThread: () => ({
          conversationId: "conv-input",
          agentId: "agent1",
          agentName: "Test Agent",
          lastActivity: Date.now(),
        }),
        registerThread: vi.fn(),
        updateActivity: vi.fn(),
      } as any);

      vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
        data: {
          id: "board1",
          name: "Board",
          members: [
            { agentId: "agent1", displayName: "Test Agent" },
          ],
        },
      } as any);

      vi.spyOn(chatApi, "startConversation").mockResolvedValue(
        "conv-input",
      );

      renderThread();

      await waitFor(() => {
        expect(
          screen.getByText("Input field output"),
        ).toBeInTheDocument();
      });
    });
  });

  describe("Error handling", () => {
    /**
     * A failed turn used to be appended to the transcript as a message with
     * `role: "agent"` reading "Sorry, I encountered an error." — so an expired
     * token, an exceeded quota, an undeployed agent and a paused conversation
     * all rendered identically, in the agent's voice, while the backend's own
     * sentence went to `console.error` and nowhere else.
     */
    async function send(text: string) {
      const user = userEvent.setup();
      await user.type(screen.getByPlaceholderText(/Message Test Agent/i), text);
      await user.click(screen.getByRole("button", { name: /Send/i }));
      return user;
    }

    it("reports a failed send as an error, not as something the agent said", async () => {
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          () => {
            throw new Error("Upstream model unavailable");
          },
        ),
      });
      renderThread();
      await waitForInit();
      await send("Will fail");

      const error = await screen.findByTestId("thread-send-error");
      // The backend's own sentence, which the old handler discarded.
      expect(error).toHaveTextContent("Upstream model unavailable");
      expect(screen.queryByText(/Sorry, I encountered an error/i)).not.toBeInTheDocument();
      expect(screen.queryByText("Agent reply")).not.toBeInTheDocument();
    });

    it("retries the failed turn", async () => {
      let attempt = 0;
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          () => {
            attempt++;
            if (attempt === 1) throw new Error("Transient failure");
            return streamOf("Second time lucky");
          },
        ),
      });
      renderThread();
      await waitForInit();
      const user = await send("Try me");

      await user.click(await screen.findByTestId("thread-retry"));
      expect(await screen.findByText("Second time lucky")).toBeInTheDocument();
      expect(screen.queryByTestId("thread-send-error")).not.toBeInTheDocument();

      // Exactly one copy of what was sent. `handleSend` appends its own
      // optimistic message, so a retry that does not withdraw the failed turn's
      // leaves the same text on screen twice.
      expect(screen.getAllByText("Try me")).toHaveLength(1);
    });

    it("keeps the failed message visible while the error is on screen", async () => {
      // Withdrawing it at failure time would fix the duplicate but hide what
      // the reader had just typed, next to an error asking them to retry it.
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          () => {
            throw new Error("Nope");
          },
        ),
      });
      renderThread();
      await waitForInit();
      await send("Still here");

      await screen.findByTestId("thread-send-error");
      expect(screen.getByText("Still here")).toBeInTheDocument();
    });

    it("keeps the question above a partial reply when the stream then errors", async () => {
      // Tokens arrived, then the turn failed. Withdrawing the question on retry
      // would leave that half-answer sitting above the NEXT question rather
      // than below the one it belongs to.
      let attempt = 0;
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          async function* () {
            attempt++;
            if (attempt === 1) {
              yield { type: "token", data: "Partial reply" };
              yield { type: "error", data: JSON.stringify({ message: "Upstream died" }) };
              return;
            }
            yield { type: "token", data: "Complete reply" };
            yield { type: "done", data: "" };
          },
        ),
      });
      renderThread();
      await waitForInit();
      const user = await send("Ask once");

      const error = await screen.findByTestId("thread-send-error");
      expect(error).toHaveTextContent("Upstream died");
      expect(screen.getByText("Partial reply")).toBeInTheDocument();

      await user.click(screen.getByTestId("thread-retry"));
      expect(await screen.findByText("Complete reply")).toBeInTheDocument();

      // The first question survives, so the partial answer still sits under it.
      const asked = screen.getAllByText("Ask once");
      expect(asked).toHaveLength(2);
    });

    it("says a paused conversation is paused, and does not offer a pointless retry", async () => {
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          () => {
            // A 409 is the backend refusing the send WITHOUT consuming it.
            throw Object.assign(new Error("conflict"), { status: 409, url: "/agents/x" });
          },
        ),
      });
      renderThread();
      await waitForInit();
      await send("While paused");

      const error = await screen.findByTestId("thread-send-error");
      expect(error).toHaveTextContent(/paused/i);
      // Retrying cannot help until the pause is decided; the queue that decides
      // it is one link away.
      expect(screen.queryByTestId("thread-retry")).not.toBeInTheDocument();
      expect(screen.getByTestId("thread-review-approvals")).toHaveAttribute(
        "href",
        "/manage/approvals",
      );
      // The message was never received, so it must not sit in the transcript
      // looking sent.
      expect(screen.queryByText("While paused")).not.toBeInTheDocument();
    });

    it("reports a stream that stops before it finishes, keeping what arrived", async () => {
      // Settling silently presented a truncated reply as a finished one, with
      // no way to tell and nothing to do about it.
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          async function* () {
            yield { type: "token", data: "Half an answer" };
            // Ends without `done` — a dropped connection, not a finished turn.
          },
        ),
      });
      renderThread();
      await waitForInit();
      await send("Long one");

      const error = await screen.findByTestId("thread-send-error");
      expect(error).toHaveTextContent(/connection ended/i);
      // The partial reply is real content and stays.
      expect(screen.getByText("Half an answer")).toBeInTheDocument();
      // The question stays above it, so re-sending appends below rather than
      // leaving the half-answer stranded above the next question.
      expect(screen.getByText("Long one")).toBeInTheDocument();
    });

    it("does not report a stream the reader stopped", async () => {
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          async function* (
            _env: string,
            _agentId: string,
            _convId: string,
            _input: unknown,
            signal?: AbortSignal,
          ) {
            yield { type: "token", data: "Partial" };
            await new Promise((_resolve, reject) => {
              signal?.addEventListener("abort", () =>
                reject(new DOMException("aborted", "AbortError")),
              );
            });
          },
        ),
      });
      renderThread();
      await waitForInit();
      const user = await send("Stop me");

      await screen.findByText(/Partial/);
      await user.click(screen.getByTestId("thread-stop"));

      await waitFor(() =>
        expect(screen.queryByTestId("thread-stop")).not.toBeInTheDocument(),
      );
      // Stopping is a decision, not a failure.
      expect(screen.queryByTestId("thread-send-error")).not.toBeInTheDocument();
    });

    it("streams the reply as it arrives, and can be stopped", async () => {
      setupMocks({
        sendMessageStreaming: (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(
          async function* (
            _env: string,
            _agentId: string,
            _convId: string,
            _input: unknown,
            signal?: AbortSignal,
          ) {
            yield { type: "token", data: "Half " };
            // Never completes on its own. The real generator reads from a fetch
            // body, so aborting rejects its `reader.read()`; the double has to
            // honour the signal the same way or it would prove nothing.
            await new Promise((_resolve, reject) => {
              signal?.addEventListener("abort", () =>
                reject(new DOMException("aborted", "AbortError")),
              );
            });
          },
        ),
      });
      renderThread();
      await waitForInit();
      const user = await send("Long answer please");

      // Text is on screen before the turn is over — the whole point of
      // streaming, and what the blocking POST could never do.
      expect(await screen.findByText(/Half/)).toBeInTheDocument();

      await user.click(screen.getByTestId("thread-stop"));
      await waitFor(() =>
        expect(screen.queryByTestId("thread-stop")).not.toBeInTheDocument(),
      );
      // What arrived is kept.
      expect(screen.getByText(/Half/)).toBeInTheDocument();
    });
  });
});
