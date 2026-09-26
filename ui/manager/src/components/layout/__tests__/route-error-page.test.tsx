import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { RouteErrorPage } from "../route-error-page";

/**
 * An error thrown above the app's own boundaries (a provider) reached React
 * Router's built-in "Unexpected Application Error!" page — English-only and
 * unstyled. The splat route now carries this errorElement.
 */
describe("RouteErrorPage", () => {
  it("renders the app's own fallback for an error thrown by the route element", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    function BrokenProvider(): never {
      throw new Error("provider exploded");
    }
    const router = createMemoryRouter(
      [{ path: "*", element: <BrokenProvider />, errorElement: <RouteErrorPage /> }],
      { initialEntries: ["/manage"] },
    );
    render(<RouterProvider router={router} />);

    expect(screen.getByTestId("route-error-page")).toHaveTextContent("Something went wrong");
    expect(screen.getByText("provider exploded")).toBeInTheDocument();
    expect(screen.getByTestId("route-error-reload")).toHaveTextContent("Reload");
    expect(screen.queryByText(/Unexpected Application Error/)).not.toBeInTheDocument();
  });
});
