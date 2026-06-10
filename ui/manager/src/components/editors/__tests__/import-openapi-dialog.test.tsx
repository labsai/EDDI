import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ImportOpenApiDialog } from "@/components/editors/import-openapi-dialog";

// Mock the discover and resource APIs
const mockDiscoverEndpoints = vi.fn();
vi.mock("@/lib/api/openapi-discover", () => ({
  discoverEndpoints: (...args: unknown[]) => mockDiscoverEndpoints(...args),
}));

const mockCreateResource = vi.fn();
vi.mock("@/lib/api/resources", () => ({
  createResource: (...args: unknown[]) => mockCreateResource(...args),
  getResourceType: vi.fn().mockReturnValue({
    slug: "apicalls",
    store: "apicallstore",
    plural: "apicalls",
  }),
}));

const mockUpdateDescriptor = vi.fn();
vi.mock("@/lib/api/descriptors", () => ({
  updateDescriptor: (...args: unknown[]) => mockUpdateDescriptor(...args),
}));

vi.mock("@/lib/api/agents", () => ({
  parseResourceUri: vi.fn(() => ({
    id: "test-123",
    version: 1,
  })),
}));

const sampleDiscoveryResult = {
  title: "Pet Store API",
  baseUrl: "https://petstore.swagger.io",
  endpointCount: 3,
  groups: {
    pets: {
      targetServerUrl: "https://petstore.swagger.io",
      httpCalls: [
        {
          name: "listPets",
          description: "List all pets",
          actions: ["list_pets"],
          request: { path: "/pets", method: "GET", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
        },
        {
          name: "createPet",
          description: "Create a pet",
          actions: ["create_pet"],
          request: { path: "/pets", method: "POST", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
        },
      ],
    },
    users: {
      targetServerUrl: "https://petstore.swagger.io",
      httpCalls: [
        {
          name: "getUser",
          description: "Get a user",
          actions: ["get_user"],
          request: { path: "/users/{id}", method: "GET", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
        },
      ],
    },
  },
};

describe("ImportOpenApiDialog", () => {
  const onClose = vi.fn();
  const onImport = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    mockCreateResource.mockResolvedValue({ location: "eddi://ai.labs.apicalls/apicallstore/apicalls/test-123?version=1" });
    mockUpdateDescriptor.mockResolvedValue(undefined);
  });

  it("returns null when not open", () => {
    renderWithProviders(
      <ImportOpenApiDialog open={false} onClose={onClose} onImport={onImport} />
    );
    expect(
      screen.queryByTestId("import-openapi-dialog")
    ).not.toBeInTheDocument();
  });

  it("renders dialog when open", () => {
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    expect(
      screen.getByTestId("import-openapi-dialog")
    ).toBeInTheDocument();
  });

  it("shows title", () => {
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    expect(screen.getByText("Import from OpenAPI")).toBeInTheDocument();
  });

  it("shows spec URL input", () => {
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    expect(
      screen.getByTestId("import-spec-url-input")
    ).toBeInTheDocument();
  });

  it("shows discover button", () => {
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    expect(screen.getByTestId("import-discover-btn")).toBeInTheDocument();
  });

  it("discover button is disabled when URL is empty", () => {
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    expect(screen.getByTestId("import-discover-btn")).toBeDisabled();
  });

  it("calls onClose when close button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    const closeBtn = screen.getByLabelText("Close");
    await user.click(closeBtn);
    expect(onClose).toHaveBeenCalled();
  });

  // ─── Discovery flow ─────────────────────────────────────────────────

  it("enables discover button when valid URL is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.example.com/openapi.json");
    expect(screen.getByTestId("import-discover-btn")).not.toBeDisabled();
  });

  it("keeps discover button disabled for invalid URL", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );
    await user.type(screen.getByTestId("import-spec-url-input"), "not-a-valid-url");
    expect(screen.getByTestId("import-discover-btn")).toBeDisabled();
  });

  it("calls discoverEndpoints when discover button is clicked", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://petstore.swagger.io/v3/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await waitFor(() => {
      expect(mockDiscoverEndpoints).toHaveBeenCalledWith("https://petstore.swagger.io/v3/openapi.json");
    });
  });

  it("shows discovered groups after successful discovery", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://petstore.swagger.io/v3/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    // Wait for groups to appear
    expect(await screen.findByTestId("import-group-pets")).toBeInTheDocument();
    expect(screen.getByTestId("import-group-users")).toBeInTheDocument();

    // Check API title and endpoint count
    expect(screen.getByText(/Pet Store API/)).toBeInTheDocument();
    expect(screen.getByText(/3.*endpoints total/)).toBeInTheDocument();
  });

  it("shows error message when discovery fails", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockRejectedValue(new Error("Failed to fetch spec"));

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://bad.url/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    expect(await screen.findByText("Failed to fetch spec")).toBeInTheDocument();
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });

  it("shows error with default message for non-Error rejects", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockRejectedValue("some string error");

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://bad.url/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    expect(await screen.findByText("Could not parse OpenAPI spec")).toBeInTheDocument();
  });

  // ─── Group selection ────────────────────────────────────────────────

  it("selects all groups by default after discovery", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByTestId("import-group-pets");

    // Check the selection count
    expect(screen.getByText(/2 \/ 2.*groups selected/)).toBeInTheDocument();
  });

  it("toggles group selection when clicked", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByTestId("import-group-pets");

    // Deselect "pets" group
    await user.click(screen.getByTestId("import-group-pets"));

    // Now only 1 selected
    expect(screen.getByText(/1 \/ 2.*groups selected/)).toBeInTheDocument();
  });

  // ─── Import action ─────────────────────────────────────────────────

  it("shows import button after discovery", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    expect(await screen.findByTestId("import-confirm-btn")).toBeInTheDocument();
  });

  it("creates resources and calls onImport when import is clicked", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByTestId("import-confirm-btn");
    await user.click(screen.getByTestId("import-confirm-btn"));

    await waitFor(() => {
      expect(mockCreateResource).toHaveBeenCalledTimes(2); // 2 groups
      expect(mockUpdateDescriptor).toHaveBeenCalledTimes(2);
      expect(onImport).toHaveBeenCalledWith(
        expect.arrayContaining([
          expect.objectContaining({ type: "ai.labs.apicalls" }),
        ])
      );
      expect(onClose).toHaveBeenCalled();
    });
  });

  it("disables import button when no groups are selected", async () => {
    const user = userEvent.setup();
    const singleGroupResult = {
      ...sampleDiscoveryResult,
      groups: {
        pets: sampleDiscoveryResult.groups.pets,
      },
    };
    mockDiscoverEndpoints.mockResolvedValue(singleGroupResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByTestId("import-group-pets");

    // Deselect the only group
    await user.click(screen.getByTestId("import-group-pets"));

    expect(screen.getByTestId("import-confirm-btn")).toBeDisabled();
  });

  it("shows error when import fails", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);
    mockCreateResource.mockRejectedValue(new Error("Create failed"));

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByTestId("import-confirm-btn");
    await user.click(screen.getByTestId("import-confirm-btn"));

    expect(await screen.findByText("Create failed")).toBeInTheDocument();
  });

  // ─── Enter key ─────────────────────────────────────────────────────

  it("triggers discovery when Enter is pressed in URL input", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    const input = screen.getByTestId("import-spec-url-input");
    await user.type(input, "https://api.test.com/openapi.json{Enter}");

    await waitFor(() => {
      expect(mockDiscoverEndpoints).toHaveBeenCalled();
    });
  });

  // ─── Cancel button ─────────────────────────────────────────────────

  it("shows cancel button in footer and calls onClose", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByText("Cancel");
    await user.click(screen.getByText("Cancel"));

    expect(onClose).toHaveBeenCalled();
  });

  it("shows endpoint previews in group labels", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue(sampleDiscoveryResult);

    renderWithProviders(
      <ImportOpenApiDialog open={true} onClose={onClose} onImport={onImport} />
    );

    await user.type(screen.getByTestId("import-spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("import-discover-btn"));

    await screen.findByTestId("import-group-pets");

    // Group should show endpoint count
    expect(screen.getByText(/2.*endpoints/)).toBeInTheDocument();
    // Should show endpoint method/path preview
    expect(screen.getByText(/GET \/pets/)).toBeInTheDocument();
  });
});
