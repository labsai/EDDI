import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";
import { ChannelDetailPage } from "@/pages/channel-detail";

function renderChannelDetail(id = "ch1", version = 1) {
  return renderPage(
    `/manage/channels/${id}?version=${version}`,
    <ChannelDetailPage />,
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
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true
      });
    } else {
      vi.spyOn(navigator.clipboard, 'writeText').mockImplementation(writeTextMock);
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
    const header = within(targetCard).getAllByText("default")[0].closest("div[class*='cursor-pointer']");
    if (header) {
      await user.click(header);
      // After collapsing, the name input should not be visible
      await waitFor(() => {
        expect(within(targetCard).queryByTestId("target-name-0")).not.toBeInTheDocument();
      });
    }
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
});
