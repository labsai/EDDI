import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { GdprPage } from "@/pages/gdpr";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-gdpr">
          <GdprPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("GDPR Privacy Admin Page", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText(/Privacy & Compliance/i)).toBeInTheDocument();
  });

  it("renders the user ID input", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-user-id")).toBeInTheDocument();
  });

  it("renders export and delete buttons", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-export-btn")).toBeInTheDocument();
    expect(screen.getByTestId("gdpr-delete-btn")).toBeInTheDocument();
  });

  it("disables buttons when user ID is empty", () => {
    renderPage();
    const exportBtn = screen.getByTestId("gdpr-export-btn");
    const deleteBtn = screen.getByTestId("gdpr-delete-btn");
    expect(exportBtn).toBeDisabled();
    expect(deleteBtn).toBeDisabled();
  });

  it("enables buttons when user ID is entered", async () => {
    renderPage();
    const input = screen.getByTestId("gdpr-user-id");
    fireEvent.change(input, { target: { value: "user-123" } });

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-btn")).not.toBeDisabled();
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });
  });

  it("shows confirmation dialog when delete is clicked", async () => {
    renderPage();
    const input = screen.getByTestId("gdpr-user-id");
    fireEvent.change(input, { target: { value: "user-123" } });

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });

    fireEvent.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });
  });

  it("shows deletion results after confirming delete", async () => {
    renderPage();
    const input = screen.getByTestId("gdpr-user-id");
    fireEvent.change(input, { target: { value: "user-123" } });

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });

    fireEvent.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });

    // Click confirm in the dialog
    const confirmBtn = screen.getByText(/Yes, Delete All Data/i);
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-results")).toBeInTheDocument();
    });

    // Check the result cards show the mock values
    expect(screen.getByText("14")).toBeInTheDocument(); // memoriesDeleted
    expect(screen.getByText("7")).toBeInTheDocument();  // conversationsDeleted
  });

  it("renders the legal notice banner", () => {
    renderPage();
    expect(screen.getByText(/Data Protection Notice/i)).toBeInTheDocument();
  });
});
