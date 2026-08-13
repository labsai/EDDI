import { describe, expect, it, vi } from "vitest";
import { createEvent, fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { DiscussionInput } from "@/components/groups/discussion-input";

describe("DiscussionInput", () => {
  it("renders the textarea", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.getByTestId("discussion-input")).toBeInTheDocument();
  });

  it("renders the submit button", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.getByTestId("start-discussion-btn")).toBeInTheDocument();
  });

  it("submit button is disabled when input is empty", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
  });

  it("submit button enables when text is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    await user.type(screen.getByTestId("discussion-input"), "Test question");
    expect(screen.getByTestId("start-discussion-btn")).not.toBeDisabled();
  });

  it("calls onSubmit with trimmed question", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "  Test question  ");
    await user.click(screen.getByTestId("start-discussion-btn"));

    expect(onSubmit).toHaveBeenCalledWith("Test question");
  });

  it("clears input after submitting", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    const input = screen.getByTestId("discussion-input");
    await user.type(input, "Question");
    await user.click(screen.getByTestId("start-discussion-btn"));

    expect(input).toHaveValue("");
  });

  it("sends on Enter key", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "Enter test{Enter}");
    expect(onSubmit).toHaveBeenCalledWith("Enter test");
  });

  it("does not send on Shift+Enter", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(
      screen.getByTestId("discussion-input"),
      "Line 1{Shift>}{Enter}{/Shift}Line 2"
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("disables textarea when disabled prop is true", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} disabled />);
    expect(screen.getByTestId("discussion-input")).toBeDisabled();
  });

  it("disables textarea when isLoading is true", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} isLoading />);
    expect(screen.getByTestId("discussion-input")).toBeDisabled();
  });

  it("shows spinner when isLoading", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} isLoading />);
    const btn = screen.getByTestId("start-discussion-btn");
    const spinner = btn.querySelector(".animate-spin");
    expect(spinner).not.toBeNull();
  });

  it("shows keyboard hint when text is entered", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    await user.type(screen.getByTestId("discussion-input"), "text");
    expect(screen.getByText(/Enter to send/)).toBeInTheDocument();
  });

  it("does not show keyboard hint when input is empty", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.queryByText(/Enter to send/)).not.toBeInTheDocument();
  });

  it("has expand button", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    const expandIcon = document.querySelector("svg.lucide-expand");
    expect(expandIcon).not.toBeNull();
  });

  it("does not submit empty/whitespace-only input", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "   ");
    expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
  });

  describe("mode='continue'", () => {
    it("shows continue placeholder instead of new-discussion placeholder", () => {
      renderWithProviders(<DiscussionInput onSubmit={vi.fn()} mode="continue" />);
      const textarea = screen.getByTestId("discussion-input");
      expect(textarea).toHaveAttribute(
        "placeholder",
        expect.stringContaining("follow-up"),
      );
    });

    it("shows Continue label on submit button", () => {
      renderWithProviders(<DiscussionInput onSubmit={vi.fn()} mode="continue" />);
      expect(screen.getByTestId("start-discussion-btn")).toHaveTextContent("Continue");
    });

    it("shows RotateCw icon instead of Send", () => {
      renderWithProviders(<DiscussionInput onSubmit={vi.fn()} mode="continue" />);
      const btn = screen.getByTestId("start-discussion-btn");
      expect(btn.querySelector("svg.lucide-rotate-cw")).not.toBeNull();
      expect(btn.querySelector("svg.lucide-send")).toBeNull();
    });
  });

  describe("disabled with message", () => {
    it("shows disabledMessage as placeholder when disabled", () => {
      renderWithProviders(
        <DiscussionInput
          onSubmit={vi.fn()}
          disabled
          disabledMessage="This discussion is closed"
        />,
      );
      const textarea = screen.getByTestId("discussion-input");
      expect(textarea).toHaveAttribute("placeholder", "This discussion is closed");
    });

    it("button is disabled when disabled prop is true", () => {
      renderWithProviders(
        <DiscussionInput onSubmit={vi.fn()} disabled disabledMessage="Closed" />,
      );
      expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
    });

    it("expand button is disabled when component is disabled", () => {
      renderWithProviders(
        <DiscussionInput onSubmit={vi.fn()} disabled disabledMessage="Closed" />,
      );
      const expandBtn = screen.getByRole("button", { name: /expand/i });
      expect(expandBtn).toBeDisabled();
    });

    it("blocks submission when disabled even if text is present", () => {
      const onSubmit = vi.fn();
      renderWithProviders(
        <DiscussionInput onSubmit={onSubmit} disabled disabledMessage="Closed" />,
      );
      expect(screen.getByTestId("discussion-input")).toBeDisabled();
      expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
      expect(onSubmit).not.toHaveBeenCalled();
    });
  });
});

describe("DiscussionInput — paste and drag-drop", () => {
  const pasteFile = (target: HTMLElement, file: File) => {
    const pasteEvent = createEvent.paste(target);
    Object.defineProperty(pasteEvent, "clipboardData", {
      value: { files: [file], types: ["Files"], getData: () => "" },
    });
    fireEvent(target, pasteEvent);
  };

  it("pasting a file stages it as an attachment chip", async () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    pasteFile(
      screen.getByTestId("discussion-input"),
      new File(["png-bytes"], "screenshot.png", { type: "image/png" }),
    );

    expect(await screen.findByTestId("discussion-attachments")).toHaveTextContent("screenshot.png");
  });

  it("dropping a file on the input area stages it, with the overlay shown mid-drag", async () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    const form = screen.getByTestId("discussion-input").closest("form")!;
    const dataTransfer = {
      files: [new File(["doc"], "notes.txt", { type: "text/plain" })],
      types: ["Files"],
    };
    fireEvent.dragEnter(form, { dataTransfer });
    expect(screen.getByTestId("file-drop-overlay")).toBeInTheDocument();
    fireEvent.drop(form, { dataTransfer });

    expect(await screen.findByTestId("discussion-attachments")).toHaveTextContent("notes.txt");
    expect(screen.queryByTestId("file-drop-overlay")).not.toBeInTheDocument();
  });

  it("a continuation ignores paste and drop — the backend rejects attachments there", async () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} mode="continue" />);

    pasteFile(
      screen.getByTestId("discussion-input"),
      new File(["x"], "late.png", { type: "image/png" }),
    );
    fireEvent.dragEnter(screen.getByTestId("discussion-input").closest("form")!, {
      dataTransfer: { files: [], types: ["Files"] },
    });

    expect(screen.queryByTestId("file-drop-overlay")).not.toBeInTheDocument();
    expect(screen.queryByTestId("discussion-attachments")).not.toBeInTheDocument();
  });
});
