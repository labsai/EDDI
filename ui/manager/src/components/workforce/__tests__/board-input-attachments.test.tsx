import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { BoardInput } from "@/components/workforce/board-input";
import { MAX_GROUP_QUESTION_CHARS } from "@/lib/api/groups";

/**
 * The regression: `BoardInput` staged a `File` that `workforce-board`'s
 * `onSend(question: string)` never read. TypeScript accepts a narrower handler,
 * so the file was silently dropped on send — paperclip, chip, no error, no
 * upload. These pin the wire shape the group endpoint actually takes.
 */
function pickFile(name = "notes.txt", type = "text/plain", body = "hello") {
  return new File([body], name, { type });
}

async function attach(file: File) {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')!;
  await userEvent.upload(input, file);
}

describe("BoardInput attachments", () => {
  it("sends staged files as base64 refs alongside the question", async () => {
    const onSend = vi.fn();
    renderWithProviders(<BoardInput onSend={onSend} />);

    await attach(pickFile());
    await waitFor(() => expect(screen.getByTestId("board-attachments")).toBeInTheDocument());

    await userEvent.type(screen.getByRole("textbox"), "What do we think?");
    await userEvent.click(screen.getByTestId("board-send"));

    await waitFor(() => expect(onSend).toHaveBeenCalled());
    const [question, attachments] = onSend.mock.calls[0]!;
    expect(question).toBe("What do we think?");
    expect(attachments).toHaveLength(1);
    expect(attachments[0]).toMatchObject({ fileName: "notes.txt", mimeType: "text/plain" });
    // Bare base64, no `data:` prefix — what `AttachmentRef.data` expects.
    expect(typeof attachments[0].data).toBe("string");
    expect(attachments[0].data).not.toContain("data:");
  });

  it("still calls onSend with one argument when nothing is attached", async () => {
    const onSend = vi.fn();
    renderWithProviders(<BoardInput onSend={onSend} />);
    await userEvent.type(screen.getByRole("textbox"), "Plain question");
    await userEvent.click(screen.getByTestId("board-send"));
    await waitFor(() => expect(onSend).toHaveBeenCalledWith("Plain question"));
  });

  it("stages several files, where the old single-File state could hold one", async () => {
    const onSend = vi.fn();
    renderWithProviders(<BoardInput onSend={onSend} />);
    await attach(pickFile("a.txt"));
    await attach(pickFile("b.txt"));
    await waitFor(() =>
      expect(screen.getByTestId("board-attachments").querySelectorAll("li")).toHaveLength(2),
    );
  });

  it("drops an attachment when its chip is dismissed", async () => {
    const onSend = vi.fn();
    renderWithProviders(<BoardInput onSend={onSend} />);
    await attach(pickFile("a.txt"));
    await waitFor(() => expect(screen.getByTestId("board-attachments")).toBeInTheDocument());
    await userEvent.click(screen.getByRole("button", { name: /remove/i }));
    await waitFor(() =>
      expect(screen.queryByTestId("board-attachments")).not.toBeInTheDocument(),
    );
  });

  it("hides the picker on a continuation, which the backend rejects", () => {
    renderWithProviders(<BoardInput onSend={vi.fn()} mode="continue" />);
    expect(screen.queryByLabelText(/attach file/i)).not.toBeInTheDocument();
  });

  it("blocks an over-long question instead of uploading it for a 400", async () => {
    const onSend = vi.fn();
    renderWithProviders(<BoardInput onSend={onSend} />);
    const textarea = screen.getByRole("textbox");
    // `type()` on 50k characters is far too slow; set the value directly and
    // fire the change the component listens for.
    await userEvent.click(textarea);
    await userEvent.paste("x".repeat(MAX_GROUP_QUESTION_CHARS + 1));

    expect(await screen.findByTestId("board-question-too-long")).toBeInTheDocument();
    expect(screen.getByTestId("board-send")).toBeDisabled();
    await userEvent.click(screen.getByTestId("board-send"));
    expect(onSend).not.toHaveBeenCalled();
  });
});
