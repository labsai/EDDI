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

  it("shows loading screen when keycloak auth is initializing", async () => {
    renderWithAuth("keycloak");

    // The global mock's init() resolves with false, but not synchronously, so
    // the first render is the loading screen. (This used to be
    // `getByTestId(a) || getByTestId(b)`, which could not fail: getByTestId
    // throws rather than returning a falsy value.)
    expect(screen.getByTestId("auth-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("auth-method")).not.toBeInTheDocument();

    // …and an init without a session never falls through to the app.
    expect(await screen.findByTestId("auth-signed-out")).toBeInTheDocument();
    expect(screen.queryByTestId("auth-method")).not.toBeInTheDocument();
  });

  it("does not show user section when auth is disabled", () => {
    renderWithAuth("none");

    expect(screen.getByTestId("auth-user")).toHaveTextContent("none");
  });
});
