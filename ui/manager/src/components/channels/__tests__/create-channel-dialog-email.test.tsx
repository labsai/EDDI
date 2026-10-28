import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateChannelDialog } from "@/components/channels/create-channel-dialog";

const toastMock = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }));
vi.mock("sonner", () => ({ toast: toastMock }));

describe("CreateChannelDialog — email", () => {
  beforeEach(() => {
    toastMock.error.mockClear();
    toastMock.warning.mockClear();
  });

  it("creates a trigger-mode email integration and surfaces the save-time warnings", async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post("*/channelstore/channels", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/channelstore/channels/mail1?version=1",
            "X-Eddi-Warnings": "email.open-trigger,email.some-future-code",
          },
        });
      }),
    );
    const user = userEvent.setup();
    const onCreated = vi.fn();
    renderWithProviders(<CreateChannelDialog open onOpenChange={vi.fn()} onCreated={onCreated} />);

    await user.type(screen.getByTestId("create-channel-name"), "Invoices");
    await user.selectOptions(screen.getByTestId("create-channel-type"), "email");
    await user.click(screen.getByTestId("create-channel-next"));

    expect(screen.getByTestId("email-platform-fields")).toBeInTheDocument();
    expect(screen.getByTestId("create-channel-next")).toBeDisabled();
    await user.type(screen.getByTestId("email-connection"), "exchange-mailbox");
    await user.type(screen.getByTestId("email-host"), "outlook.office365.com");
    await user.type(screen.getByTestId("email-address"), "invoices@example.com");
    await user.click(screen.getByTestId("email-require-authentication"));
    expect(screen.getByTestId("email-open-trigger-warning")).toBeInTheDocument();
    await user.click(screen.getByTestId("create-channel-next"));

    await user.type(screen.getByTestId("create-target-agent"), "agent1");
    await user.click(screen.getByTestId("create-channel-submit"));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith("mail1"));
    expect(body?.channelType).toBe("email");
    expect(body?.platformConfig).toEqual({
      mode: "trigger",
      transport: "imap",
      connection: "exchange-mailbox",
      host: "outlook.office365.com",
      address: "invoices@example.com",
      requireAuthentication: "false",
    });
    expect(toastMock.warning).toHaveBeenCalledTimes(2);
    expect(String(toastMock.warning.mock.calls[0]![0])).toContain("anyone on the internet");
    // An unknown code is shown as it is rather than dropped.
    expect(toastMock.warning.mock.calls[1]![0]).toBe("email.some-future-code");
  });

  it("does not let the credentials step pass while attachments are stored without MIME types", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateChannelDialog open onOpenChange={vi.fn()} />);
    await user.type(screen.getByTestId("create-channel-name"), "Invoices");
    await user.selectOptions(screen.getByTestId("create-channel-type"), "email");
    await user.click(screen.getByTestId("create-channel-next"));
    await user.type(screen.getByTestId("email-connection"), "exchange-mailbox");
    await user.type(screen.getByTestId("email-host"), "outlook.office365.com");
    await user.type(screen.getByTestId("email-address"), "invoices@example.com");
    expect(screen.getByTestId("create-channel-next")).toBeEnabled();

    await user.click(screen.getByText("Content, attachments and limits"));
    await user.selectOptions(screen.getByTestId("email-attachments"), "store");
    expect(screen.getByTestId("create-channel-next")).toBeDisabled();
    expect(screen.getByTestId("email-problem-mimeTypesRequired")).toBeInTheDocument();
  });
});
