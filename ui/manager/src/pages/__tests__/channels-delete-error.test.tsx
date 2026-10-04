import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChannelsPage } from "@/pages/channels";
import { server } from "@/test/mocks/server";

const toastMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: toastMock }));

describe("ChannelsPage delete failure", () => {
  it("toasts the server reason and closes the dialog instead of rejecting unhandled", async () => {
    server.use(
      http.delete("*/channelstore/channels/:id", () =>
        HttpResponse.json({ message: "Channel is in use" }, { status: 409 }),
      ),
    );
    renderWithProviders(<ChannelsPage />, { initialRoute: "/manage/channels" });
    await waitFor(() => {
      expect(screen.getAllByTestId(/^channel-card-/).length).toBeGreaterThanOrEqual(1);
    });
    const user = userEvent.setup();
    await user.click(screen.getAllByTitle(/delete/i)[0]!);
    await user.click(await screen.findByText(/^Delete$/));

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledTimes(1));
    expect(String(toastMock.error.mock.calls[0]![0])).toContain("Channel is in use");
    await waitFor(() => {
      expect(screen.queryByText("Delete channel?")).not.toBeInTheDocument();
    });
  });
});
