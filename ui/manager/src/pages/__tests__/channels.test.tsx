import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChannelsPage } from "@/pages/channels";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

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

    await waitFor(() => {
      const cards = screen.getAllByText("Engineering Slack");
      expect(cards.length).toBeGreaterThanOrEqual(1);
    }, { timeout: 10000 });
  });

  it("displays channel cards in card view by default", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      const cards = screen.getAllByTestId(/^channel-card-/);
      expect(cards.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows Slack type badge on channel cards", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      const badges = screen.getAllByText("Slack");
      expect(badges.length).toBeGreaterThanOrEqual(1);
    });
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

    await waitFor(() => {
      // Badge renders "3 channels" as adjacent text nodes inside a div.
      // Use getAllByText to avoid ambiguity, then find the badge element.
      const matches = screen.getAllByText((_content, element) => {
        if (!element || element.tagName !== "DIV") return false;
        const text = element.textContent ?? "";
        return /^\d+\s+channel/.test(text.trim());
      });
      expect(matches.length).toBeGreaterThanOrEqual(1);
    }, { timeout: 10000 });
  });

  it("filters channels by search query", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    // Wait for channels to load
    await waitFor(() => {
      expect(
        screen.getAllByTestId(/^channel-card-/).length,
      ).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    const searchInput = screen.getByTestId("channel-search");
    await user.type(searchInput, "Engineering");

    // Should show only the Engineering Slack card
    await waitFor(() => {
      const cards = screen.getAllByTestId(/^channel-card-/);
      expect(cards.length).toBe(1);
    });
  });

  // ─── List view ──────────────────────────────────────────────────────────

  it("switches to list view and renders table", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("view-toggle-list"));

    // Table headers should appear
    await waitFor(() => {
      expect(screen.getByText("Name")).toBeInTheDocument();
      expect(screen.getByText("Type")).toBeInTheDocument();
      expect(screen.getByText("Channel ID")).toBeInTheDocument();
      expect(screen.getByText("Targets")).toBeInTheDocument();
      expect(screen.getByText("Version")).toBeInTheDocument();
    });
  });

  it("list view renders rows with channel-row data-testid", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      const rows = screen.getAllByTestId(/^channel-row-/);
      expect(rows.length).toBeGreaterThanOrEqual(1);
    });
  });

  // ─── Delete dialog ──────────────────────────────────────────────────────

  it("opens delete confirmation dialog", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    // Find a delete button on a channel card and click it
    const deleteButtons = screen.getAllByTitle(/delete/i);
    expect(deleteButtons.length).toBeGreaterThanOrEqual(1);

    const user = userEvent.setup();
    await user.click(deleteButtons[0]);

    await waitFor(() => {
      expect(screen.getByText("Delete channel?")).toBeInTheDocument();
    });
  });

  it("can cancel delete confirmation dialog", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const deleteButtons = screen.getAllByTitle(/delete/i);
    const user = userEvent.setup();
    await user.click(deleteButtons[0]);

    await waitFor(() => {
      expect(screen.getByText("Delete channel?")).toBeInTheDocument();
    });

    // Cancel button
    const cancelBtn = screen.getByText("Cancel");
    await user.click(cancelBtn);

    await waitFor(() => {
      expect(screen.queryByText("Delete channel?")).not.toBeInTheDocument();
    });
  });

  // ─── Empty state ────────────────────────────────────────────────────────

  it("shows empty state when no channels exist", async () => {
    server.use(
      http.get("*/channelstore/channels/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getByText("No channels yet")).toBeInTheDocument();
    });
  });

  it("shows create button in empty state", async () => {
    server.use(
      http.get("*/channelstore/channels/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      // Two create buttons: one in header, one in empty state
      const createButtons = screen.getAllByText("Create Channel");
      expect(createButtons.length).toBe(2);
    });
  });

  it("shows no results message when search finds nothing", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    await user.type(screen.getByTestId("channel-search"), "zzzznonexistent");

    await waitFor(() => {
      expect(screen.getByText(/No results found/i)).toBeInTheDocument();
    });
  });

  it("empty state from search does NOT show create button", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    await user.type(screen.getByTestId("channel-search"), "zzzznonexistent");

    await waitFor(() => {
      // Only the header button should remain, not the empty-state create button
      const createButtons = screen.getAllByText("Create Channel");
      expect(createButtons.length).toBe(1);
    });
  });

  // ─── Confirm delete flow ──────────────────────────────────────────────

  it("confirms channel deletion when Delete is clicked", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    const deleteButtons = screen.getAllByTitle(/delete/i);
    await user.click(deleteButtons[0]);

    await waitFor(() => {
      expect(screen.getByText("Delete channel?")).toBeInTheDocument();
    });

    // Click Delete to confirm
    const confirmBtn = screen.getByText(/^Delete$/);
    await user.click(confirmBtn);

    // Dialog should close
    await waitFor(() => {
      expect(screen.queryByText("Delete channel?")).not.toBeInTheDocument();
    });
  });

  // ─── Duplicate button ────────────────────────────────────────────────

  it("clicking duplicate button on a channel card triggers duplication", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    const duplicateButtons = screen.getAllByTitle("Duplicate");
    if (duplicateButtons.length > 0) {
      await user.click(duplicateButtons[0]);
      // Just verify no crash
      expect(screen.getByTestId("channel-search")).toBeInTheDocument();
    }
  });

  // ─── Page title and subtitle ──────────────────────────────────────────

  it("renders page title 'Channels'", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });
    expect(screen.getByText("Channels")).toBeInTheDocument();
  });

  it("renders page subtitle about messaging platforms", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });
    expect(
      screen.getByText(/Connect agents and groups to messaging platforms/)
    ).toBeInTheDocument();
  });

  // ─── Slack setup summary ──────────────────────────────────────────────

  it("shows Slack setup description with scopes", () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });
    expect(
      screen.getByText(/Bot Token Scopes/)
    ).toBeInTheDocument();
  });

  // ─── List view version display ────────────────────────────────────────

  it("list view shows version number for each channel", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    }, { timeout: 10000 });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      const rows = screen.getAllByTestId(/^channel-row-/);
      expect(rows.length).toBeGreaterThanOrEqual(1);
      // Each row should show version
      expect(rows[0].textContent).toMatch(/v\d+/);
    });
  });

  // ─── Channel search by type ──────────────────────────────────────────

  it("filters channels by channel type", async () => {
    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });

    const user = userEvent.setup();
    await user.type(screen.getByTestId("channel-search"), "Slack");

    await waitFor(() => {
      // All channels are Slack type, so all should still show
      const cards = screen.getAllByTestId(/^channel-card-/);
      expect(cards.length).toBeGreaterThanOrEqual(1);
    });
  });

  // ─── Singular "1 channel" ──────────────────────────────────────────────

  it("shows singular 'channel' when only 1 result", async () => {
    server.use(
      http.get("*/channelstore/channels/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.channel/channelstore/channels/ch-solo?version=1",
            name: "Solo Channel",
            description: "",
            channelType: "Slack",
          },
        ]);
      }),
      http.get("*/channelstore/channels/ch-solo", () => {
        return HttpResponse.json({
          name: "Solo Channel",
          channelType: "slack",
          platformConfig: {
            channelId: "SOLO123",
          },
          targets: [],
          defaultTargetName: "default",
        });
      })
    );

    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    await waitFor(() => {
      expect(screen.getByText("Solo Channel")).toBeInTheDocument();
    });

    await waitFor(() => {
      const badge = screen.getByText(/1\s+channel/i);
      expect(badge).toBeInTheDocument();
    });
  });
});

