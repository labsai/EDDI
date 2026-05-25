import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChannelsPage } from "@/pages/channels";

describe("ChannelsPage", () => {
  it("renders heading and search input", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });
    expect(screen.getByTestId("channel-search")).toBeInTheDocument();
  });

  it("shows create channel button", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });
    expect(screen.getByTestId("create-channel-btn")).toBeInTheDocument();
  });

  it("renders channel cards after loading", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(
      () => {
        const cards = screen.getAllByText("Engineering Slack");
        expect(cards.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 },
    );
  });

  it("displays channel cards in card view by default", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(
      () => {
        const cards = screen.getAllByTestId(/^channel-card-/);
        expect(cards.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 },
    );
  });

  it("shows Slack type badge on channel cards", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(
      () => {
        const badges = screen.getAllByText("Slack");
        expect(badges.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 },
    );
  });

  it("opens create channel dialog on button click", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-channel-btn"));

    expect(screen.getByTestId("create-channel-name")).toBeInTheDocument();
  });

  it("shows view toggle", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
  });

  it("shows Slack setup guide banner", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    // The Slack setup guide is always visible on the channels page
    expect(screen.getByText(/Slack Setup Guide/i)).toBeInTheDocument();
  });

  it("shows link to Slack App Dashboard", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    const link = screen.getByText(/Open Slack App Dashboard/i);
    expect(link).toBeInTheDocument();
    expect(link.closest("a")).toHaveAttribute(
      "href",
      "https://api.slack.com/apps",
    );
  });

  it("shows channel count badge after loading", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(
      () => {
        // Badge renders "3 channels" as adjacent text nodes inside a div.
        // Use getAllByText to avoid ambiguity, then find the badge element.
        const matches = screen.getAllByText((_content, element) => {
          if (!element || element.tagName !== "DIV") return false;
          const text = element.textContent ?? "";
          return /^\d+\s+channel/.test(text.trim());
        });
        expect(matches.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 },
    );
  });

  it("filters channels by search query", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    // Wait for channels to load
    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^channel-card-/).length,
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 },
    );

    const user = userEvent.setup();
    const searchInput = screen.getByTestId("channel-search");
    await user.type(searchInput, "Engineering");

    // Should show only the Engineering Slack card
    await waitFor(() => {
      const cards = screen.getAllByTestId(/^channel-card-/);
      expect(cards.length).toBe(1);
    });
  });
});
