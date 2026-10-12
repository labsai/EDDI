import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Route, Routes, useLocation } from "react-router-dom";
import { toast } from "sonner";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceCard } from "../workforce-card";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

function renderCard() {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="*" element={<WorkforceCard id="g1" name="Board" version={3} />} />
      </Routes>
      <LocationProbe />
    </>,
    { initialRoute: "/workforce" },
  );
}

describe("WorkforceCard actions (review 2026-10-02)", () => {
  beforeEach(() => vi.clearAllMocks());

  it("Duplicate → Open Settings goes to the new group's settings, not /{id}?version=1/settings", async () => {
    server.use(
      http.post("*/groupstore/groups/g1", () =>
        HttpResponse.json(
          { location: "/groupstore/groups/copy42?version=1" },
          { status: 201, headers: { Location: "/groupstore/groups/copy42?version=1" } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderCard();

    await user.click(screen.getAllByTestId("workforce-menu-g1")[0]!);
    await user.click(await screen.findByRole("menuitem", { name: /Duplicate/ }));

    await waitFor(() => expect(vi.mocked(toast.success)).toHaveBeenCalled());
    const options = vi.mocked(toast.success).mock.calls[0]![1] as unknown as {
      action: { onClick: () => void };
    };
    options.action.onClick();

    await waitFor(() =>
      expect(screen.getByTestId("location")).toHaveTextContent("/workforce/copy42/settings"),
    );
    expect(screen.getByTestId("location").textContent).not.toContain("?version=1/");
  });

  it("deletes softly unless 'Delete permanently' is ticked", async () => {
    const flags: (string | null)[] = [];
    server.use(
      http.delete("*/groupstore/groups/g1", ({ request }) => {
        flags.push(new URL(request.url).searchParams.get("permanent"));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderCard();

    await user.click(screen.getAllByTestId("workforce-menu-g1")[0]!);
    await user.click(await screen.findByRole("menuitem", { name: /Delete/ }));
    expect(await screen.findByTestId("permanent-delete-checkbox")).not.toBeChecked();
    await user.click(screen.getByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(flags).toEqual(["false"]));
  });
});
