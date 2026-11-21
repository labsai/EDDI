import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Toaster } from "sonner";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderPage, userEvent } from "@eddi/app-kit/test/test-utils";
import { Link, useNavigate } from "react-router-dom";
import { ChannelDetailPage } from "@/pages/channel-detail";

const guard = vi.hoisted(() => ({ allowNextNavigation: vi.fn() }));
vi.mock("@eddi/app-kit/hooks/use-unsaved-changes-guard", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@eddi/app-kit/hooks/use-unsaved-changes-guard")>();
  return {
    ...actual,
    allowNextNavigation: () => {
      guard.allowNextNavigation();
      actual.allowNextNavigation();
    },
  };
});

function renderChannelDetail(id = "ch1", version = 1, withToaster = false) {
  return renderPage(
    `/manage/channels/${id}?version=${version}`,
    withToaster ? (
      <>
        <Toaster />
        <ChannelDetailPage />
      </>
    ) : (
      <ChannelDetailPage />
    ),
    "/manage/channels/:id",
  );
}

describe("ChannelDetailPage", () => {
  it("renders loading skeleton initially", () => {
    renderChannelDetail();
    expect(screen.getByTestId("channel-detail-loading")).toBeInTheDocument();
  });

  it("displays channel name after loading", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByText("Engineering Slack")).toBeInTheDocument();
    });
  });

  it("shows save and delete buttons", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByTestId("save-channel-btn")).toBeInTheDocument();
      expect(screen.getByTestId("delete-channel-btn")).toBeInTheDocument();
    });
  });

  it("renders General section with name and type inputs", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByTestId("channel-name-input")).toBeInTheDocument();
      expect(screen.getByTestId("channel-type-select")).toBeInTheDocument();
    });
  });

  it("renders Platform Configuration section with channel ID", async () => {
    renderChannelDetail();

    await waitFor(() => {
      const channelIdInput = screen.getByTestId("channel-id-input");
      expect(channelIdInput).toBeInTheDocument();
      expect(channelIdInput).toHaveValue("C0123ABCDEF");
    });
  });

  it("renders target cards for each target", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByTestId("target-card-0")).toBeInTheDocument();
      expect(screen.getByTestId("target-card-1")).toBeInTheDocument();
    });
  });

  it("shows default badge on the default target", async () => {
    renderChannelDetail();

    await waitFor(() => {
      const defaultBadges = screen.getAllByText("default");
      expect(defaultBadges.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("renders trigger keyword chips on the second target", async () => {
    renderChannelDetail();

    await waitFor(() => {
      // Trigger keywords render inside Badge as "{keyword}:" — use aria-label on remove buttons
      const targetCard = screen.getByTestId("target-card-1");
      expect(within(targetCard).getByLabelText("Remove faq")).toBeInTheDocument();
      expect(within(targetCard).getByLabelText("Remove help-me")).toBeInTheDocument();
    });
  });

  it("shows add target button", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByTestId("add-target-btn")).toBeInTheDocument();
    });
  });

  it("adds a new target card when add target is clicked", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("add-target-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("add-target-btn"));

    expect(screen.getByTestId("target-card-2")).toBeInTheDocument();
  });

  it("allows editing the channel name", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("channel-name-input")).toBeInTheDocument();
    });

    const nameInput = screen.getByTestId("channel-name-input");
    await user.clear(nameInput);
    await user.type(nameInput, "Updated Channel");
    expect(nameInput).toHaveValue("Updated Channel");
  });

  it("renders the raw JSON toggle", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByText("Raw Configuration")).toBeInTheDocument();
    });
  });

  it("shows webhook URL section", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(
        screen.getByText(/\/integrations\/slack\/events/),
      ).toBeInTheDocument();
    });
  });

  it("shows required Bot Token Scopes", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByText("chat:write")).toBeInTheDocument();
      expect(screen.getByText("app_mentions:read")).toBeInTheDocument();
      expect(screen.getByText("im:history")).toBeInTheDocument();
    });
  });

  it("shows required Event Subscriptions", async () => {
    renderChannelDetail();

    await waitFor(() => {
      expect(screen.getByText("app_mention")).toBeInTheDocument();
      expect(screen.getByText("message.im")).toBeInTheDocument();
    });
  });

  it("renders all three target cards for ch2", async () => {
    renderChannelDetail("ch2", 2);

    await waitFor(() => {
      // ch2 has 3 targets (support, review-panel, observer)
      expect(screen.getByTestId("target-card-0")).toBeInTheDocument();
      expect(screen.getByTestId("target-card-1")).toBeInTheDocument();
      expect(screen.getByTestId("target-card-2")).toBeInTheDocument();
    });
  });

  it("shows target name inputs for ch2", async () => {
    renderChannelDetail("ch2", 2);

    await waitFor(() => {
      // ch2 target names should be populated in the name inputs
      const nameInput1 = screen.getByTestId("target-name-1") as HTMLInputElement;
      expect(nameInput1.value).toBe("review-panel");
    });
  });

  // ─── Interaction tests ──────────────────────────────────────────────────

  it("keeps a card's unsaved input with that card when a card above it is removed", async () => {
    // Cards were keyed by index: removing the first handed its local state (a
    // half-typed trigger) to whichever card slid into its slot.
    renderChannelDetail("ch2", 2);
    const user = userEvent.setup();
    await screen.findByTestId("target-card-2");

    await user.type(screen.getByTestId("target-trigger-input-1"), "half-typed");
    await user.click(screen.getByTestId("remove-target-0"));

    await waitFor(() => expect(screen.queryByTestId("target-card-2")).not.toBeInTheDocument());
    expect((screen.getByTestId("target-name-0") as HTMLInputElement).value).toBe("review-panel");
    expect((screen.getByTestId("target-trigger-input-0") as HTMLInputElement).value).toBe("half-typed");
    expect((screen.getByTestId("target-trigger-input-1") as HTMLInputElement).value).toBe("");
  });

  it("removes a target card when remove button is clicked", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("target-card-1")).toBeInTheDocument();
    });

    // Click the Remove button on the second target
    const targetCard = screen.getByTestId("target-card-1");
    const removeBtn = within(targetCard).getByText("Remove");
    await user.click(removeBtn);

    await waitFor(() => {
      expect(screen.queryByTestId("target-card-1")).not.toBeInTheDocument();
    });
  });

  it("sets a non-default target as default", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("target-card-1")).toBeInTheDocument();
    });

    // The second target is not default, it should have a "Set as Default" button
    const targetCard = screen.getByTestId("target-card-1");
    const setDefaultBtn = within(targetCard).getByText("Set as Default");
    await user.click(setDefaultBtn);

    // After clicking, the second target should now show the default badge
    await waitFor(() => {
      const defaultBadges = within(screen.getByTestId("target-card-1")).queryAllByText("default");
      expect(defaultBadges.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("adds a trigger keyword via Enter key", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("target-card-0")).toBeInTheDocument();
    });

    const targetCard = screen.getByTestId("target-card-0");
    const triggerInput = within(targetCard).getByPlaceholderText("Add trigger...");
    await user.type(triggerInput, "billing{enter}");

    // The new trigger should appear as a chip
    await waitFor(() => {
      expect(within(targetCard).getByLabelText("Remove billing")).toBeInTheDocument();
    });
  });

  it("copies webhook URL to clipboard", async () => {
    const writeTextMock = vi.fn().mockResolvedValue(undefined);
    let clipboardSpy: ReturnType<typeof vi.spyOn> | undefined;
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true
      });
    } else {
      clipboardSpy = vi.spyOn(navigator.clipboard, 'writeText').mockImplementation(writeTextMock as never) as unknown as ReturnType<typeof vi.spyOn>;
    }

    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText(/\/integrations\/slack\/events/)).toBeInTheDocument();
    });

    // Click the Copy button next to the webhook URL
    const copyBtn = screen.getByText("Copy");
    await user.click(copyBtn);

    expect(writeTextMock).toHaveBeenCalledWith(
      expect.stringContaining("/integrations/slack/events")
    );

    // Restore clipboard spy to avoid leaking into other tests
    clipboardSpy?.mockRestore();
  });

  it("collapses a target card when header is clicked", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("target-card-0")).toBeInTheDocument();
    });

    // The target card should be expanded by default (showing name input)
    const targetCard = screen.getByTestId("target-card-0");
    expect(within(targetCard).getByTestId("target-name-0")).toBeInTheDocument();

    // Click the header to collapse
    // A real button now, so it is reachable from the keyboard and announces its state.
    const header = within(targetCard).getAllByText("default")[0]!.closest("button");
    expect(header).toBeTruthy();
    expect(header).toHaveAttribute("aria-expanded", "true");

    await user.click(header!);
    expect(header).toHaveAttribute("aria-expanded", "false");
    // After collapsing, the name input should not be visible
    await waitFor(() => {
      expect(within(targetCard).queryByTestId("target-name-0")).not.toBeInTheDocument();
    });
  });

  it("expands raw JSON section", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Raw Configuration")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Raw Configuration"));

    await waitFor(() => {
      // The JSON should now be visible
      expect(screen.getByText(/"channelType"/)).toBeInTheDocument();
    });
  });

  // ── Regression: save/delete errors must be surfaced, not swallowed ─────────
  it("shows an error toast when saving fails (no silent failure)", async () => {
    server.use(
      http.put("*/channelstore/channels/:id", () =>
        HttpResponse.json(
          { message: "Duplicate trigger 'help' across targets" },
          { status: 400 },
        ),
      ),
    );
    renderChannelDetail("ch1", 1, true);
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("save-channel-btn")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("save-channel-btn"));
    await waitFor(() => {
      expect(screen.getByText(/Duplicate trigger/)).toBeInTheDocument();
    });
  });

  // ── Regression: renaming the default target keeps defaultTargetName valid ──
  it("keeps defaultTargetName in sync when the default target is renamed", async () => {
    let saved: { defaultTargetName?: string } | undefined;
    server.use(
      http.put("*/channelstore/channels/:id", async ({ request }) => {
        saved = (await request.json()) as { defaultTargetName?: string };
        return HttpResponse.json(
          { location: "/channelstore/channels/ch1?version=2" },
          { status: 200 },
        );
      }),
    );
    renderChannelDetail("ch1", 1);
    const user = userEvent.setup();
    // ch1 target[0] is named "default" and IS the default target.
    const nameInput = await screen.findByTestId("target-name-0");
    expect(nameInput).toHaveValue("default");
    await user.clear(nameInput);
    await user.type(nameInput, "renamed");
    await user.click(screen.getByTestId("save-channel-btn"));
    await waitFor(() => {
      expect(saved?.defaultTargetName).toBe("renamed");
    });
  });

  it("lets the navigation after a delete through the unsaved-changes guard", async () => {
    // The draft is still dirty when navigate() runs (setBaseline has not
    // re-rendered), so without the bypass the blocker would hold the user on a
    // page whose channel no longer exists.
    guard.allowNextNavigation.mockClear();
    server.use(
      http.delete("*/channelstore/channels/:id", () => new HttpResponse(null, { status: 204 })),
    );
    renderChannelDetail("ch1", 1);
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("delete-channel-btn"));
    await user.click(await screen.findByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(guard.allowNextNavigation).toHaveBeenCalledTimes(1));
  });
});

describe("ChannelDetailPage — email integration", () => {
  const emailChannel = {
    name: "Invoices Mailbox",
    channelType: "email",
    platformConfig: {
      connection: "exchange-mailbox",
      host: "outlook.office365.com",
      address: "invoices@example.com",
    },
    targets: [
      { name: "default", type: "AGENT", targetId: "agent1", triggers: [], observeMode: false, observeConfig: null },
      { name: "urgent", type: "AGENT", targetId: "agent2", triggers: ["urgent"], observeMode: false, observeConfig: null },
    ],
    defaultTargetName: "default",
  };

  function useEmailChannel() {
    server.use(http.get("*/channelstore/channels/:id", () => HttpResponse.json(emailChannel)));
  }

  it("shows the email form and setup notes instead of a webhook URL", async () => {
    useEmailChannel();
    renderChannelDetail("mail1", 1);
    await waitFor(() => expect(screen.getByTestId("email-platform-fields")).toBeInTheDocument());
    expect(screen.getByTestId("email-host")).toHaveValue("outlook.office365.com");
    expect(screen.getByTestId("email-setup-section")).toBeInTheDocument();
    expect(screen.queryByText("Webhook URL")).toBeNull();
    expect(screen.getByTestId("target-trigger-hint-1").textContent).toContain("invoices+urgent@");
  });

  it("saves the fixed mode and transport keys and toasts the save-time warning", async () => {
    let saved: { platformConfig?: Record<string, string> } | undefined;
    useEmailChannel();
    server.use(
      http.put("*/channelstore/channels/:id", async ({ request }) => {
        saved = (await request.json()) as typeof saved;
        return new HttpResponse(null, {
          status: 200,
          headers: {
            Location: "/channelstore/channels/mail1?version=2",
            "X-Eddi-Warnings": "email.tools-without-approvals",
          },
        });
      }),
    );
    renderChannelDetail("mail1", 1, true);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("save-channel-btn")).toBeInTheDocument());
    await user.click(screen.getByTestId("save-channel-btn"));

    await waitFor(() => expect(saved?.platformConfig?.mode).toBe("trigger"));
    expect(saved?.platformConfig).toEqual({ ...emailChannel.platformConfig, mode: "trigger", transport: "imap" });
    await waitFor(() => expect(screen.getByText(/has tools and no tool approvals/)).toBeInTheDocument());
  });

  it("refuses to save an email integration the backend would reject, without a request", async () => {
    let requests = 0;
    useEmailChannel();
    server.use(
      http.put("*/channelstore/channels/:id", () => {
        requests += 1;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderChannelDetail("mail1", 1, true);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("email-host")).toBeInTheDocument());
    await user.clear(screen.getByTestId("email-host"));
    await user.type(screen.getByTestId("email-host"), "10.0.0.5");
    await user.click(screen.getByTestId("save-channel-btn"));

    await waitFor(() => expect(screen.getAllByText(/Use a DNS name, not an IP address/).length).toBeGreaterThan(1));
    expect(requests).toBe(0);
  });
});

describe("ChannelDetailPage — switching the channel type", () => {
  it("drops the webhook keys when a webhook channel becomes an email one, and none come back", async () => {
    const user = userEvent.setup();
    renderChannelDetail("ch4", 1);
    await waitFor(() => expect(screen.getByTestId("webhook-platform-fields")).toBeInTheDocument());

    await user.selectOptions(screen.getByTestId("channel-type-select"), "email");
    expect(screen.getByTestId("email-platform-fields")).toBeInTheDocument();
    await user.click(screen.getByText("Raw Configuration"));
    const raw = () => JSON.parse(screen.getByText(/"channelType"/).textContent ?? "{}") as { platformConfig: Record<string, string> };
    expect(raw().platformConfig).toEqual({ mode: "trigger", transport: "imap" });

    await user.selectOptions(screen.getByTestId("channel-type-select"), "webhook");
    expect(raw().platformConfig).toEqual({});
    expect(screen.getByTestId("webhook-signing-secret")).toBeInTheDocument();
  });
});

describe("ChannelDetailPage — a link without ?version (the owner digest's)", () => {
  function BackButton() {
    const navigate = useNavigate();
    return <button type="button" data-testid="go-back" onClick={() => void navigate(-1)}>back</button>;
  }

  function renderWithLinks(path: string) {
    return renderPage(
      path,
      <>
        <Link to="/manage/channels/ch-email#mail-log" data-testid="go-email">email</Link>
        <Link to="/manage/channels/ch1" data-testid="go-ch1">ch1</Link>
        <Link to="/manage/channels/ch4?version=1" data-testid="go-ch4">ch4</Link>
        <BackButton />
        <ChannelDetailPage />
      </>,
      "/manage/channels/:id",
    );
  }

  it("opens the current version, not version 1", async () => {
    const requested: string[] = [];
    server.use(
      http.get("*/channelstore/channels/ch1/currentversion", () => HttpResponse.json(3)),
      http.get("*/channelstore/channels/ch1", ({ request }) => {
        requested.push(new URL(request.url).searchParams.get("version") ?? "");
        return HttpResponse.json({ name: "Engineering Slack v3", channelType: "slack", platformConfig: {}, targets: [] });
      }),
    );
    renderWithLinks("/manage/channels/ch1");

    expect(await screen.findByTestId("channel-name-input")).toHaveValue("Engineering Slack v3");
    expect(requested).toContain("3");
    expect(requested).not.toContain("1"); // never version 1
  });

  it("shows a retryable error when the version cannot be resolved, and never loads version 1", async () => {
    let fail = true;
    const requested: string[] = [];
    server.use(
      http.get("*/channelstore/channels/ch1/currentversion", () =>
        fail ? HttpResponse.json({ error: "unavailable" }, { status: 503 }) : HttpResponse.json(2),
      ),
      http.get("*/channelstore/channels/ch1", ({ request }) => {
        requested.push(new URL(request.url).searchParams.get("version") ?? "");
        return HttpResponse.json({ name: "Engineering Slack v2", channelType: "slack", platformConfig: {}, targets: [] });
      }),
    );
    renderWithLinks("/manage/channels/ch1");

    expect(await screen.findByTestId("channel-detail-error")).toBeInTheDocument();
    expect(requested).toEqual([]);

    fail = false;
    await userEvent.click(within(screen.getByTestId("channel-detail-error")).getByTestId("error-state-retry"));
    expect(await screen.findByTestId("channel-name-input")).toHaveValue("Engineering Slack v2");
    expect(requested).toEqual(["2"]);
  });

  it("does not reuse an earlier answer when the same page asks again and the lookup fails", async () => {
    let fail = false;
    server.use(
      http.get("*/channelstore/channels/ch1/currentversion", () =>
        fail ? HttpResponse.json({ error: "unavailable" }, { status: 503 }) : HttpResponse.json(1),
      ),
    );
    renderWithLinks("/manage/channels/ch1");
    expect(await screen.findByText("Engineering Slack")).toBeInTheDocument();

    fail = true;
    await userEvent.click(screen.getByTestId("go-ch1"));

    expect(await screen.findByTestId("channel-detail-error")).toBeInTheDocument();
  });

  it("never shows the previous channel's draft under the next channel while its version resolves", async () => {
    let release: () => void = () => {};
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.get("*/channelstore/channels/ch-email/currentversion", async () => {
        await pending;
        return HttpResponse.json(1);
      }),
    );
    renderWithLinks("/manage/channels/ch1?version=1");
    expect(await screen.findByText("Engineering Slack")).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("go-email"));

    expect(await screen.findByTestId("channel-detail-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("channel-name-input")).not.toBeInTheDocument(); // no editor for A under B
    expect(screen.queryByTestId("save-channel-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("delete-channel-btn")).not.toBeInTheDocument();

    release();
    expect(await screen.findByTestId("channel-name-input")).toHaveValue("Invoice intake");
  });

  it("asks again on Back instead of reusing a lookup still pending from the earlier visit", async () => {
    const answers: Array<() => void> = [];
    let asked = 0;
    server.use(
      http.get("*/channelstore/channels/ch1/currentversion", async () => {
        // Every answer is held until the end: the first visit's lookup is still
        // pending when the user leaves and comes back.
        asked++;
        await new Promise<void>((resolve) => answers.push(resolve));
        return HttpResponse.json(1);
      }),
    );
    renderWithLinks("/manage/channels/ch1");
    await waitFor(() => expect(answers.length).toBeGreaterThan(0));
    await new Promise((resolve) => setTimeout(resolve, 20));
    const before = asked;

    await userEvent.click(screen.getByTestId("go-ch4"));
    expect(await screen.findByTestId("channel-name-input")).toHaveValue("Billing Webhook");
    await userEvent.click(screen.getByTestId("go-back"));

    await waitFor(() => expect(asked).toBeGreaterThan(before)); // a new request for this visit
    answers.forEach((release) => release());
    expect(await screen.findByTestId("channel-name-input")).toHaveValue("Engineering Slack");
  });

  it("shows no mail log on an older email version when the integration is no longer email", async () => {
    server.use(
      http.get("*/channelstore/channels/ch-email/currentversion", () => HttpResponse.json(2)),
      http.get("*/channelstore/channels/ch-email", ({ request }) =>
        new URL(request.url).searchParams.get("version") === "2"
          ? HttpResponse.json({ name: "Now Slack", channelType: "slack", platformConfig: {}, targets: [] })
          : HttpResponse.json({ name: "Invoice intake", channelType: "EMAIL", platformConfig: { mode: "trigger", transport: "imap" }, targets: [] }),
      ),
    );
    renderWithLinks("/manage/channels/ch-email?version=1");
    expect(await screen.findByTestId("channel-name-input")).toHaveValue("Invoice intake");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("email-mail-log-section")).not.toBeInTheDocument();
  });

  it("shows the mail log for a saved type of EMAIL (case-insensitive, as the server)", async () => {
    server.use(
      http.get("*/channelstore/channels/ch-email", () =>
        HttpResponse.json({ name: "Invoice intake", channelType: "EMAIL", platformConfig: { mode: "trigger", transport: "imap" }, targets: [] }),
      ),
    );
    renderWithLinks("/manage/channels/ch-email?version=1");
    expect(await screen.findByTestId("email-mail-log-section")).toBeInTheDocument();
  });
});
