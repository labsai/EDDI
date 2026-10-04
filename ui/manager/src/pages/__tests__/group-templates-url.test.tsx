import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useLocation } from "react-router-dom";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupTemplatesPage } from "@/pages/group-templates";

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname + location.search}</span>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <>
      <GroupTemplatesPage />
      <LocationProbe />
    </>,
    { initialRoute: route },
  );
}

/**
 * The chosen template used to live in component state. The error view's Back
 * link pointed at the very URL the user was on, and the browser's Back button
 * left the page instead of returning to the gallery.
 */
describe("GroupTemplatesPage — selection lives in the URL", () => {
  it("records the choice as a search param and clears it on the in-page Back", async () => {
    const user = userEvent.setup();
    renderPage("/manage/groups/templates");
    await waitFor(() => screen.getByTestId("template-card-research-pod"));

    await user.click(screen.getByTestId("template-card-research-pod"));
    await waitFor(() => screen.getByTestId("template-back"));
    expect(screen.getByTestId("location")).toHaveTextContent(
      "/manage/groups/templates?template=research-pod",
    );

    await user.click(screen.getByTestId("template-back"));
    await waitFor(() => expect(screen.getByTestId("template-gallery")).toBeInTheDocument());
    expect(screen.getByTestId("location")).toHaveTextContent(/^\/manage\/groups\/templates$/);
  });

  it("opens straight into the template named in the URL", async () => {
    renderPage("/manage/groups/templates?template=research-pod");

    await waitFor(() => {
      expect(screen.getByTestId("template-role-input-researcher1")).toBeInTheDocument();
    });
  });

  it("offers a Back link that leaves the broken template for the gallery", async () => {
    server.use(
      http.get("*/groupstore/templates/does-not-exist", () => new HttpResponse(null, { status: 404 })),
    );
    const user = userEvent.setup();
    renderPage("/manage/groups/templates?template=does-not-exist");

    const back = await screen.findByRole("link", { name: "Back to templates" });
    expect(back).toHaveAttribute("href", "/manage/groups/templates");
    await user.click(back);
    await waitFor(() => expect(screen.getByTestId("template-gallery")).toBeInTheDocument());
  });

  it("points wizard users at the starter presets and names the humanised role", async () => {
    renderPage("/manage/groups/templates");
    await waitFor(() => screen.getByTestId("template-card-research-pod"));

    expect(screen.getByTestId("templates-wizard-note")).toHaveTextContent(/starter presets/i);
    expect(screen.getByRole("link", { name: "Open the group wizard" })).toHaveAttribute(
      "href",
      "/manage/groups/wizard",
    );
  });
});
