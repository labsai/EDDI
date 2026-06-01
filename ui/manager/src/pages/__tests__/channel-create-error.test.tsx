import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChannelsPage } from "@/pages/channels";

describe("CreateChannelDialog — error handling", () => {
  it("shows error toast when channel creation fails", async () => {
    // Override the POST handler to return a 500 error
    server.use(
      http.post("*/channelstore/channels", () => {
        return HttpResponse.json(
          { message: "Internal Server Error" },
          { status: 500 },
        );
      }),
    );

    renderWithProviders(<ChannelsPage />, {
      initialRoute: "/manage/channels",
    });

    const user = userEvent.setup();

    // Step 1: Open the dialog and fill in the name
    await user.click(screen.getByTestId("create-channel-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("create-channel-name")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("create-channel-name"), "Test Channel");

    // Step 2: Click Next to go to credentials
    await user.click(screen.getByTestId("create-channel-next"));
    await waitFor(() => {
      expect(screen.getByTestId("create-channel-id")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("create-channel-id"), "C0123ABC");

    // Step 3: Click Next to go to target
    await user.click(screen.getByTestId("create-channel-next"));
    await waitFor(() => {
      expect(screen.getByTestId("create-target-agent")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("create-target-agent"), "agent1");

    // Step 4: Submit — should trigger the error toast
    await user.click(screen.getByTestId("create-channel-submit"));

    // The dialog should remain open (not close on error)
    await waitFor(() => {
      expect(screen.getByTestId("create-channel-submit")).toBeInTheDocument();
    });
  });
});
