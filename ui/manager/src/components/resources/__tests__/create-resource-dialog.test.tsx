import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateResourceDialog } from "../create-resource-dialog";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("CreateResourceDialog", () => {
  const mockOnClose = vi.fn();

  beforeEach(() => {
    mockOnClose.mockReset();
    server.resetHandlers();

    // Default MSW mocks for resource creation
    server.use(
      http.post("*/rulestore/rulesets", () => {
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/rulestore/rulesets/new-rule-id-123",
          },
        });
      }),
      http.post("*/snippetstore/snippets", () => {
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/snippetstore/snippets/new-snippet-id-123",
          },
        });
      })
    );
  });

  it("does not render when open is false", () => {
    const { container } = renderWithProviders(
      <CreateResourceDialog open={false} onClose={mockOnClose} typeSlug="rules" typeName="Ruleset" />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders when open is true, showing correct title and default names", () => {
    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="rules" typeName="Ruleset" />
    );

    expect(screen.getByText("Create Ruleset")).toBeInTheDocument();
    const input = screen.getByTestId("resource-name-input") as HTMLInputElement;
    expect(input.value).toBe("New Ruleset");
  });

  it("uses workflowName prefix for defaultName", () => {
    renderWithProviders(
      <CreateResourceDialog
        open={true}
        onClose={mockOnClose}
        typeSlug="rules"
        typeName="Ruleset"
        workflowName="MyWorkflow"
      />
    );

    const input = screen.getByTestId("resource-name-input") as HTMLInputElement;
    expect(input.value).toBe("MyWorkflow — Ruleset");
  });

  it("automatically sanitizes snippet names to lowercase and underscores", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="snippets" typeName="Snippet" />
    );

    const input = screen.getByTestId("resource-name-input") as HTMLInputElement;
    // Snippets default to 'new_snippet'
    expect(input.value).toBe("new_snippet");

    // Clear and type invalid characters
    await user.clear(input);
    await user.type(input, "My New Snippet 123!");
    
    // Invalid characters (spaces, uppercase, exclamation) should be converted to underscores
    expect(input.value).toBe("my_new_snippet_123_");
  });

  it("shows validation error for snippet names if empty or invalid", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="snippets" typeName="Snippet" />
    );

    const input = screen.getByTestId("resource-name-input") as HTMLInputElement;
    await user.clear(input);

    expect(screen.getByText("Snippet name is required.")).toBeInTheDocument();

    const submitBtn = screen.getByTestId("create-resource-submit");
    expect(submitBtn).toBeDisabled();
  });

  it("submits the form successfully and navigates to the detail page", async () => {
    const user = userEvent.setup();
    let mutationPayload: Record<string, unknown> | null = null;

    server.use(
      http.post("*/rulestore/rulesets", async ({ request }) => {
        const bodyText = await request.text();
        mutationPayload = bodyText ? JSON.parse(bodyText) : {};
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/rulestore/rulesets/new-rule-id-123",
          },
        });
      })
    );

    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="rules" typeName="Ruleset" />
    );

    const input = screen.getByTestId("resource-name-input");
    await user.clear(input);
    await user.type(input, "Custom Ruleset");

    const descInput = screen.getByTestId("resource-description-input");
    await user.type(descInput, "Test ruleset description");

    const submitBtn = screen.getByTestId("create-resource-submit");
    await user.click(submitBtn);

    await waitFor(() => {
      expect(mutationPayload).not.toBeNull();
      expect(mockOnClose).toHaveBeenCalled();
    });
  });

  it("submits prompt snippet successfully with custom fields embedded", async () => {
    const user = userEvent.setup();
    let mutationPayload: Record<string, unknown> | null = null;

    server.use(
      http.post("*/snippetstore/snippets", async ({ request }) => {
        const bodyText = await request.text();
        mutationPayload = bodyText ? JSON.parse(bodyText) : {};
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/snippetstore/snippets/custom_snippet_12",
          },
        });
      })
    );

    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="snippets" typeName="Snippet" />
    );

    const input = screen.getByTestId("resource-name-input");
    await user.clear(input);
    await user.type(input, "custom_snippet_12");

    const descInput = screen.getByTestId("resource-description-input");
    await user.type(descInput, "Test snippet desc");

    const submitBtn = screen.getByTestId("create-resource-submit");
    await user.click(submitBtn);

    await waitFor(() => {
      expect(mutationPayload).not.toBeNull();
      expect(mutationPayload.name).toBe("custom_snippet_12");
      expect(mutationPayload.description).toBe("Test snippet desc");
      expect(mockOnClose).toHaveBeenCalled();
    });
  });

  it("handles submission error and displays error message", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("*/rulestore/rulesets", () => {
        return HttpResponse.json({ errorMessage: "Invalid resource configuration" }, { status: 400 });
      })
    );

    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="rules" typeName="Ruleset" />
    );

    const submitBtn = screen.getByTestId("create-resource-submit");
    await user.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByTestId("create-resource-error")).toHaveTextContent("Invalid resource configuration");
    });
  });

  it("closes dialog when backdrop or close button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="rules" typeName="Ruleset" />
    );

    const closeBtn = screen.getByTestId("create-resource-close");
    await user.click(closeBtn);
    expect(mockOnClose).toHaveBeenCalledTimes(1);

    const backdrop = screen.getByTestId("create-resource-backdrop");
    await user.click(backdrop);
    expect(mockOnClose).toHaveBeenCalledTimes(2);
  });

  it("closes dialog when Escape key is pressed", () => {
    renderWithProviders(
      <CreateResourceDialog open={true} onClose={mockOnClose} typeSlug="rules" typeName="Ruleset" />
    );

    fireEvent.keyDown(document, { key: "Escape" });
    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });
});
