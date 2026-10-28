import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { EmailPlatformFields, EmailSecurityNotes } from "@/components/channels/email-settings";
import { EMAIL_DEFAULT_INPUT_TEMPLATE } from "@/lib/email-channel";

/** The form wired to real state, so a sequence of edits accumulates. */
function Harness({ initial, onChange }: { initial: Record<string, string>; onChange: (pc: Record<string, string>) => void }) {
  const [pc, setPc] = useState(initial);
  return (
    <EmailPlatformFields
      advancedOpen
      platformConfig={pc}
      onChange={(next) => {
        setPc(next);
        onChange(next);
      }}
    />
  );
}

describe("EmailPlatformFields", () => {
  it("renders the backend defaults without writing them", () => {
    const onChange = vi.fn();
    renderWithProviders(<EmailPlatformFields advancedOpen platformConfig={{}} onChange={onChange} />);

    expect(screen.getByTestId("email-mode-note")).toBeInTheDocument();
    expect(screen.getByTestId("email-port")).toHaveValue("993");
    expect(screen.getByTestId("email-processed-action")).toHaveValue("flag");
    expect(screen.getByTestId("email-identity-mode")).toHaveValue("perSender");
    expect(screen.getByTestId("email-attachments")).toHaveValue("drop");
    expect(screen.getByTestId("email-require-authentication")).toBeChecked();
    expect(screen.getByTestId("email-correlate-threads")).not.toBeChecked();
    expect(screen.getByTestId("email-strip-quoted-history")).toBeChecked();
    expect(screen.getByTestId("email-folder")).toHaveAttribute("placeholder", "INBOX");
    expect(screen.getByTestId("email-poll-seconds")).toHaveAttribute("placeholder", "60");
    expect(screen.getByTestId("email-max-message-bytes")).toHaveAttribute("placeholder", "26214400");
    expect(screen.getByTestId("email-max-turns")).toHaveAttribute("placeholder", "50");
    expect(screen.getByTestId("email-input-template")).toHaveAttribute("placeholder", EMAIL_DEFAULT_INPUT_TEMPLATE);
    expect(onChange).not.toHaveBeenCalled();
    // The required fields say they are missing.
    expect(screen.getByTestId("email-problem-connectionRequired")).toBeInTheDocument();
    expect(screen.getByTestId("email-problem-hostRequired")).toBeInTheDocument();
    expect(screen.getByTestId("email-problem-addressRequired")).toBeInTheDocument();
  });

  it("writes only the keys the user set, and removes a cleared one", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={{}} onChange={onChange} />);

    await user.type(screen.getByTestId("email-host"), "imap.example.com");
    await user.selectOptions(screen.getByTestId("email-port"), "143");
    await user.click(screen.getByTestId("email-correlate-threads"));
    await user.type(screen.getByTestId("email-folder"), "X");
    await user.clear(screen.getByTestId("email-folder"));

    expect(onChange).toHaveBeenLastCalledWith({ host: "imap.example.com", port: "143", correlateThreads: "true" });
    expect(screen.queryByTestId("email-problem-hostRequired")).toBeNull();
  });

  it("writes move:<folder> for the move action", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={{}} onChange={onChange} />);

    await user.selectOptions(screen.getByTestId("email-processed-action"), "move");
    expect(screen.getByTestId("email-problem-moveFolderRequired")).toBeInTheDocument();
    await user.type(screen.getByTestId("email-move-folder"), "Done");
    expect(onChange).toHaveBeenLastCalledWith({ processedAction: "move:Done" });
    expect(screen.queryByTestId("email-problem-moveFolderRequired")).toBeNull();
  });

  it("warns that anyone can trigger the agent when there is no allowlist and no authentication", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{}} onChange={vi.fn()} />);
    expect(screen.queryByTestId("email-open-trigger-warning")).toBeNull();

    await user.click(screen.getByTestId("email-require-authentication"));
    expect(screen.getByTestId("email-open-trigger-warning")).toBeInTheDocument();

    await user.type(screen.getByTestId("email-sender-allowlist"), "*@example.com");
    expect(screen.queryByTestId("email-open-trigger-warning")).toBeNull();
  });

  it("requires MIME types once attachments are stored", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{}} onChange={vi.fn()} />);
    expect(screen.queryByTestId("email-problem-mimeTypesRequired")).toBeNull();

    await user.selectOptions(screen.getByTestId("email-attachments"), "store");
    expect(screen.getByTestId("email-problem-mimeTypesRequired")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("email-attachment-mime-types"), { target: { value: "application/pdf" } });
    expect(screen.queryByTestId("email-problem-mimeTypesRequired")).toBeNull();
  });

  it("refuses an IP literal as the host", () => {
    renderWithProviders(<EmailPlatformFields platformConfig={{ host: "192.168.1.10" }} onChange={vi.fn()} />);
    expect(screen.getByTestId("email-problem-hostIpLiteral")).toBeInTheDocument();
  });
});

describe("EmailSecurityNotes", () => {
  it("explains the per-mailbox Exchange restriction", () => {
    renderWithProviders(<EmailSecurityNotes />);
    const text = screen.getByTestId("email-exchange-note").textContent ?? "";
    expect(text).toContain("Add-MailboxPermission");
    expect(text).toContain("IMAP.AccessAsApp");
  });
});
