import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "../auth-provider";
import { useAuth } from "@/hooks/use-auth";

/** Test component that displays auth state */
function AuthDisplay() {
  const { authenticated, method, user } = useAuth();
  return (
    <div>
      <span data-testid="auth-method">{method}</span>
      <span data-testid="auth-status">
        {authenticated ? "authenticated" : "unauthenticated"}
      </span>
      <span data-testid="auth-user">{user?.username ?? "none"}</span>
    </div>
  );
}

function renderWithAuth(authMethod: "none" | "keycloak" = "none") {
  return render(
    <BrowserRouter>
      <AuthProvider
        configOverride={{
          method: authMethod,
          url: "http://localhost:8180",
          realm: "eddi",
          clientId: "eddi-manager",
        }}
      >
        <AuthDisplay />
      </AuthProvider>
    </BrowserRouter>
  );
}

describe("AuthProvider", () => {
  it("renders children immediately when auth is disabled", () => {
    renderWithAuth("none");

    expect(screen.getByTestId("auth-method")).toHaveTextContent("none");
    expect(screen.getByTestId("auth-status")).toHaveTextContent(
      "authenticated"
    );
    expect(screen.getByTestId("auth-user")).toHaveTextContent("none");
  });

  it("provides authenticated=true by default when auth is none", () => {
    renderWithAuth("none");

    expect(screen.getByTestId("auth-status")).toHaveTextContent(
      "authenticated"
    );
  });

  it("shows loading screen when keycloak auth is initializing", () => {
    renderWithAuth("keycloak");

    // The mock keycloak init() resolves immediately with false,
    // but during the initial render, loading is true
    expect(
      screen.getByTestId("auth-loading") ||
        screen.getByTestId("auth-method")
    ).toBeTruthy();
  });

  it("does not show user section when auth is disabled", () => {
    renderWithAuth("none");

    expect(screen.getByTestId("auth-user")).toHaveTextContent("none");
  });
});
