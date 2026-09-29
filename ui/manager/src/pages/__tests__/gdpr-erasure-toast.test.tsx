import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GdprPage } from "@/pages/gdpr";
import { server } from "@/test/mocks/server";

/** A partial erasure (207) used to toast "User data deleted successfully". */

const toastMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: toastMock }));

beforeEach(() => {
  for (const fn of Object.values(toastMock)) fn.mockClear();
});

async function erase() {
  renderWithProviders(<GdprPage />, { initialRoute: "/manage/gdpr" });
  const user = userEvent.setup();
  await user.type(screen.getByTestId("gdpr-user-id"), "user-123");
  await user.click(screen.getByTestId("gdpr-delete-btn"));
  await user.click(await screen.findByRole("button", { name: /yes, delete all data/i }));
  await screen.findByTestId("gdpr-results");
}

describe("GDPR erasure toast", () => {
  it("warns instead of reporting success for a partial erasure", async () => {
    server.use(
      http.delete("*/admin/gdpr/:userId", ({ params }) =>
        HttpResponse.json(
          { userId: params.userId, failedSteps: ["attachments"], complete: false },
          { status: 207 },
        ),
      ),
    );
    await erase();
    await waitFor(() => expect(toastMock.warning).toHaveBeenCalledTimes(1));
    expect(toastMock.warning.mock.calls[0]![0]).toMatch(/incomplete/i);
    expect(toastMock.success).not.toHaveBeenCalled();
  });

  it("still reports a complete erasure as a success", async () => {
    await erase();
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledTimes(1));
    expect(toastMock.warning).not.toHaveBeenCalled();
  });
});
