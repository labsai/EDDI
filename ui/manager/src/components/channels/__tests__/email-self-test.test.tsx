import { describe, it, expect, vi, afterEach } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { EmailSelfTestPanel, SELF_TEST_POLL_MS } from "@/components/channels/email-self-test";

const TOKEN = "A".repeat(43);

describe("EmailSelfTestPanel", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts the test, then polls until the mail came back and shows the findings by level", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let polls = 0;
    server.use(
      http.post("*/channelstore/channels/mail1/email-self-test", () =>
        HttpResponse.json({ status: "waiting", token: TOKEN, expiresAt: new Date(Date.now() + 900_000).toISOString(), signed: true }),
      ),
      http.get(`*/channelstore/channels/mail1/email-self-test/${TOKEN}`, () => {
        polls++;
        return HttpResponse.json({
          status: "done",
          signed: true,
          findings: [
            { code: "dkim.unaligned", level: "problem", text: "Your provider signs as x.gappssmtp.com, not example.com." },
            { code: "smime.verified", level: "ok", text: "The S/MIME signature verified." },
            { code: "dmarc.absent", level: "warning", text: "No DMARC record is published." },
          ],
        });
      }),
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<EmailSelfTestPanel integrationId="mail1" hasSmtp />);

    await user.click(screen.getByTestId("email-self-test-start"));
    await waitFor(() => expect(screen.getByTestId("email-self-test-result")).toHaveAttribute("data-status", "waiting"));
    expect(screen.getByTestId("email-self-test-start")).toBeDisabled();

    await act(async () => {
      vi.advanceTimersByTime(SELF_TEST_POLL_MS + 10);
    });
    await waitFor(() => expect(screen.getByTestId("email-self-test-result")).toHaveAttribute("data-status", "done"));
    expect(polls).toBe(1);
    expect(screen.getByTestId("email-self-test-finding-dkim.unaligned")).toHaveAttribute("data-level", "problem");
    expect(screen.getByTestId("email-self-test-finding-dkim.unaligned")).toHaveTextContent("DKIM signs for another domain");
    expect(screen.getByTestId("email-self-test-finding-dkim.unaligned")).toHaveTextContent("not example.com");
    expect(screen.getByTestId("email-self-test-finding-smime.verified")).toHaveAttribute("data-level", "ok");
    expect(screen.getByTestId("email-self-test-signed")).toHaveTextContent("(sent S/MIME-signed)");

    // Done: no further polling.
    await act(async () => {
      vi.advanceTimersByTime(SELF_TEST_POLL_MS * 3);
    });
    expect(polls).toBe(1);
  });

  it("keeps asking after the expiry time passed, so the server's last word ends the wait", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let polls = 0;
    server.use(
      http.post("*/channelstore/channels/mail1/email-self-test", () =>
        HttpResponse.json({ status: "waiting", token: TOKEN, expiresAt: new Date(Date.now() - 60_000).toISOString() }),
      ),
      http.get(`*/channelstore/channels/mail1/email-self-test/${TOKEN}`, () => {
        polls++;
        return HttpResponse.json({ status: "expired", findings: [{ code: "test.expired", level: "problem", text: "It did not come back." }] });
      }),
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<EmailSelfTestPanel integrationId="mail1" hasSmtp />);

    await user.click(screen.getByTestId("email-self-test-start"));
    await waitFor(() => expect(screen.getByTestId("email-self-test-result")).toHaveAttribute("data-status", "waiting"));
    await act(async () => {
      vi.advanceTimersByTime(SELF_TEST_POLL_MS + 10);
    });
    await waitFor(() => expect(screen.getByTestId("email-self-test-result")).toHaveAttribute("data-status", "expired"));
    expect(polls).toBe(1);
    expect(screen.getByTestId("email-self-test-start")).toBeEnabled();
  });

  it("says so when five tests ran this hour", async () => {
    server.use(http.post("*/channelstore/channels/mail1/email-self-test", () => new HttpResponse("limit", { status: 429 })));
    const user = userEvent.setup();
    renderWithProviders(<EmailSelfTestPanel integrationId="mail1" hasSmtp />);

    await user.click(screen.getByTestId("email-self-test-start"));
    expect(await screen.findByTestId("email-self-test-error")).toHaveTextContent("At most 5 self-tests per hour");
  });

  it("cannot run without an SMTP relay, and points out unsaved changes", () => {
    renderWithProviders(<EmailSelfTestPanel integrationId="mail1" hasSmtp={false} dirty />);
    expect(screen.getByTestId("email-self-test-start")).toBeDisabled();
    expect(screen.getByTestId("email-self-test-no-smtp")).toBeInTheDocument();
    expect(screen.getByTestId("email-self-test-dirty")).toBeInTheDocument();
  });

  it("shows a send failure with its reason", async () => {
    server.use(
      http.post("*/channelstore/channels/mail1/email-self-test", () =>
        HttpResponse.json({
          status: "send_failed",
          signed: false,
          findings: [{ code: "send.failed", level: "problem", text: "The test mail could not be sent (authentication): rejected." }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<EmailSelfTestPanel integrationId="mail1" hasSmtp />);
    await user.click(screen.getByTestId("email-self-test-start"));
    await waitFor(() => expect(screen.getByTestId("email-self-test-result")).toHaveAttribute("data-status", "send_failed"));
    expect(screen.getByTestId("email-self-test-finding-send.failed")).toHaveTextContent("rejected");
  });
});
