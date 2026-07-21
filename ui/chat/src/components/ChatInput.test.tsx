import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ChatInput } from "./ChatInput";
import { ChatProvider } from "@/store/chat-store";

function renderInput(props = {}) {
  const onSend = vi.fn();
  const result = render(
    <ChatProvider>
      <ChatInput onSend={onSend} {...props} />
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
    })),
  };
});

describe("ChatInput — attachments", () => {
  async function attach(fileName = "invoice.pdf") {
    const { onSend } = renderInput({ conversationId: "conv-1" });
    const input = screen.getByTestId("chat-file-input");
    const file = new File(["abc"], fileName, { type: "application/pdf" });
    fireEvent.change(input, { target: { files: [file] } });
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
});
