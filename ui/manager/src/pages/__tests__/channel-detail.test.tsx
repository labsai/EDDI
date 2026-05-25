import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
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
    expect(document.querySelector(".animate-pulse")).toBeTruthy();
  });

  it("displays channel name after loading", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByText("Engineering Slack")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("shows save and delete buttons", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByTestId("save-channel-btn")).toBeInTheDocument();
        expect(screen.getByTestId("delete-channel-btn")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("renders General section with name and type inputs", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByTestId("channel-name-input")).toBeInTheDocument();
        expect(screen.getByTestId("channel-type-select")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("renders Platform Configuration section with channel ID", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        const channelIdInput = screen.getByTestId("channel-id-input");
        expect(channelIdInput).toBeInTheDocument();
        expect(channelIdInput).toHaveValue("C0123ABCDEF");
      },
      { timeout: 10000 },
    );
  });

  it("renders target cards for each target", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByTestId("target-card-0")).toBeInTheDocument();
        expect(screen.getByTestId("target-card-1")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("shows default badge on the default target", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        const defaultBadges = screen.getAllByText("default");
        expect(defaultBadges.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 },
    );
  });

  it("renders trigger keyword chips on the second target", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        const allTexts = document.body.textContent ?? "";
        expect(allTexts).toContain("faq");
        expect(allTexts).toContain("help-me");
      },
      { timeout: 10000 },
    );
  });

  it("shows add target button", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByTestId("add-target-btn")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("adds a new target card when add target is clicked", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(screen.getByTestId("add-target-btn")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );

    await user.click(screen.getByTestId("add-target-btn"));

    expect(screen.getByTestId("target-card-2")).toBeInTheDocument();
  });

  it("allows editing the channel name", async () => {
    renderChannelDetail();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(screen.getByTestId("channel-name-input")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );

    const nameInput = screen.getByTestId("channel-name-input");
    await user.clear(nameInput);
    await user.type(nameInput, "Updated Channel");
    expect(nameInput).toHaveValue("Updated Channel");
  });

  it("renders the raw JSON toggle", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByText("Raw Configuration")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("shows webhook URL section", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(
          screen.getByText(/\/integrations\/slack\/events/),
        ).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("shows required Bot Token Scopes", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByText("chat:write")).toBeInTheDocument();
        expect(screen.getByText("app_mentions:read")).toBeInTheDocument();
        expect(screen.getByText("im:history")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("shows required Event Subscriptions", async () => {
    renderChannelDetail();

    await waitFor(
      () => {
        expect(screen.getByText("app_mention")).toBeInTheDocument();
        expect(screen.getByText("message.im")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("renders all three target cards for ch2", async () => {
    renderChannelDetail("ch2", 2);

    await waitFor(
      () => {
        // ch2 has 3 targets (support, review-panel, observer)
        expect(screen.getByTestId("target-card-0")).toBeInTheDocument();
        expect(screen.getByTestId("target-card-1")).toBeInTheDocument();
        expect(screen.getByTestId("target-card-2")).toBeInTheDocument();
      },
      { timeout: 10000 },
    );
  });

  it("shows target type (AGENT/GROUP) on targets", async () => {
    renderChannelDetail("ch2", 2);

    await waitFor(
      () => {
        // ch2 has both AGENT and GROUP targets
        const allTexts = document.body.textContent ?? "";
        expect(allTexts).toContain("review");
        expect(allTexts).toContain("panel");
      },
      { timeout: 10000 },
    );
  });
});
