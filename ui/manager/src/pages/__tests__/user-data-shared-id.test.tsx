import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { UserDataPage } from "@/pages/user-data";

describe("UserDataPage — shared user id and tab semantics", () => {
  it("keeps the typed user id when switching tabs", async () => {
    renderWithProviders(<UserDataPage />, { initialRoute: "/manage/userdata" });
    const user = userEvent.setup();

    await user.type(screen.getByTestId("memory-user-id"), "user-42");
    await user.click(screen.getByTestId("tab-properties"));

    expect(screen.getByPlaceholderText("Enter User ID...")).toHaveValue("user-42");

    await user.click(screen.getByTestId("tab-conversations"));
    expect(screen.getByTestId("uc-userid-input")).toHaveValue("user-42");
  });

  it("restores the user id from the URL", () => {
    renderWithProviders(<UserDataPage />, {
      initialRoute: "/manage/userdata?tab=memories&user=abc",
    });
    expect(screen.getByTestId("memory-user-id")).toHaveValue("abc");
  });

  it("names every tab and points the panel's aria-labelledby at a real tab", () => {
    renderWithProviders(<UserDataPage />, { initialRoute: "/manage/userdata" });

    for (const tab of ["memories", "properties", "conversations"]) {
      const el = screen.getByTestId(`tab-${tab}`);
      expect(el).toHaveAttribute("id", `tab-${tab}`);
      expect(el).toHaveAccessibleName();
    }
    const panel = screen.getByRole("tabpanel");
    const labelledBy = panel.getAttribute("aria-labelledby")!;
    expect(document.getElementById(labelledBy)).not.toBeNull();
    expect(panel).toHaveAccessibleName();
  });
});
