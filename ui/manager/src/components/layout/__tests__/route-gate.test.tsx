import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderAs } from "@eddi/app-kit/test/test-utils";
import { RouteGate } from "../route-gate";

const page = <RouteGate><div data-testid="page">page</div></RouteGate>;
const routes: Array<[string, React.ReactElement]> = [["/manage/*", page]];

describe("RouteGate", () => {
  it("renders the page for a role that may use it", () => {
    renderAs(["eddi-editor"], "/manage/agents", routes);
    expect(screen.getByTestId("page")).toBeInTheDocument();
    expect(screen.queryByTestId("no-access")).not.toBeInTheDocument();
  });

  it("replaces an admin-only page with the no-access page for an editor, naming both roles", () => {
    renderAs(["eddi-editor"], "/manage/gdpr", routes);
    expect(screen.queryByTestId("page")).not.toBeInTheDocument();
    expect(screen.getByTestId("no-access")).toBeInTheDocument();
    const text = screen.getByTestId("no-access-roles").textContent ?? "";
    expect(text).toContain("editor");
    expect(text).toContain("administrator");
  });

  it("shows a viewer no-access on the authoring pages but lets it open the cluster console", () => {
    renderAs(["eddi-viewer"], "/manage/agents", routes);
    expect(screen.getByTestId("no-access")).toBeInTheDocument();
  });

  it("opens the cluster console for a viewer", () => {
    renderAs(["eddi-viewer"], "/manage/coordinator", routes);
    expect(screen.getByTestId("page")).toBeInTheDocument();
  });

  it("explains the guided setup to an editor and points at Quick Create", () => {
    renderAs(["eddi-editor"], "/manage/agents/wizard", routes);
    expect(screen.getByTestId("no-access-guided-setup")).toBeInTheDocument();
    expect(screen.getByTestId("no-access-quick-create")).toHaveAttribute("href", "/manage/agents");
  });

  it("shows nothing different for an admin", () => {
    renderAs(["eddi-admin"], "/manage/agents/wizard", routes);
    expect(screen.getByTestId("page")).toBeInTheDocument();
  });

  it("renders every page with auth off (no gate at all)", async () => {
    const { render } = await import("@testing-library/react");
    const { MemoryRouter, Routes, Route } = await import("react-router-dom");
    render(
      <MemoryRouter initialEntries={["/manage/gdpr"]}>
        <Routes>
          <Route path="/manage/*" element={page} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId("page")).toBeInTheDocument();
  });
});
