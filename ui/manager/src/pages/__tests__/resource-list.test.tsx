import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";
import { ResourceListPage } from "@/pages/resource-list";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderResourceList(type = "rules") {
  return renderPage(
    `/manage/resources/${type}`,
    <ResourceListPage />,
    "/manage/resources/:type"
  );
}

describe("ResourceListPage", () => {
  it("renders the page with type name in heading", async () => {
    renderResourceList("rules");
    await waitFor(() => {
      expect(screen.getByText("Rules")).toBeInTheDocument();
    });
  });

  it("renders the search input", () => {
    renderResourceList("rules");
    expect(screen.getByTestId("resource-search")).toBeInTheDocument();
  });

  it("renders the view toggle", () => {
    renderResourceList("rules");
    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
  });

  it("shows create resource button with type name", () => {
    renderResourceList("rules");
    expect(screen.getByTestId("create-resource-btn")).toBeInTheDocument();
    expect(screen.getByText("Create Rules")).toBeInTheDocument();
  });

  it("shows back to resources link", () => {
    renderResourceList("rules");
    expect(screen.getByTestId("back-to-list")).toBeInTheDocument();
    expect(screen.getByText("Back to Resources")).toBeInTheDocument();
  });

  it("shows resource cards in card view after data loads", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });
  });

  it("shows resource names from mock data", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      // The generic wildcard handler returns "Mock res1"
      expect(screen.getByText("Mock res1")).toBeInTheDocument();
    });
  });

  it("shows resource count after data loads", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByText("1 resource")).toBeInTheDocument();
    });
  });

  it("shows resource list table in list view", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("resource-list")).toBeInTheDocument();
    });
  });

  it("toggles between card and list view", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("resource-list")).toBeInTheDocument();
    });

    // Switch back to card
    await user.click(screen.getByTestId("view-toggle-card"));

    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });
  });

  it("list view shows table headers", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByText("Name")).toBeInTheDocument();
      expect(screen.getByText("ID")).toBeInTheDocument();
      expect(screen.getByText("Version")).toBeInTheDocument();
      expect(screen.getByText("Modified")).toBeInTheDocument();
      expect(screen.getByText("Actions")).toBeInTheDocument();
    });
  });

  it("renders correct heading for LLM type", async () => {
    renderResourceList("llm");

    await waitFor(() => {
      expect(screen.getByText("LLM")).toBeInTheDocument();
      expect(screen.getByText("Create LLM")).toBeInTheDocument();
    });
  });

  it("shows error state for unknown resource type", () => {
    renderResourceList("unknown-type");
    expect(screen.getByText("Unknown resource type")).toBeInTheDocument();
  });

  it("shows link back to resources when type is unknown", () => {
    renderResourceList("unknown-type");
    // "Back to Resources" appears in the unknown type view
    const links = screen.getAllByText("Back to Resources");
    expect(links.length).toBeGreaterThan(0);
  });

  it("shows empty state when no resources exist", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderResourceList("rules");

    await waitFor(() => {
      expect(
        screen.getByText(/Create your first/)
      ).toBeInTheDocument();
    });
  });

  it("shows error state on API failure", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    });
  });

  it("shows retry button on error", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  it("search triggers API refetch with filter", async () => {
    renderResourceList("rules");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });

    // Type in search box
    await user.type(screen.getByTestId("resource-search"), "Escalation");

    // The mock handler for descriptors uses filter param
    // Whatever filter was sent will show up as "Mock Escalation"
    await waitFor(() => {
      expect(screen.getByText("Mock Escalation")).toBeInTheDocument();
    });
  });

  it("shows description text for the resource type", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(
        screen.getByText(
          "Define conversation flow rules and decision logic"
        )
      ).toBeInTheDocument();
    });
  });

  it("shows resource card with menu button", async () => {
    renderResourceList("rules");

    await waitFor(() => {
      expect(screen.getByTestId("resource-card-res1")).toBeInTheDocument();
      expect(screen.getByTestId("resource-menu-res1")).toBeInTheDocument();
    });
  });
});
