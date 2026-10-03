import { StrictMode } from "react";
import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { AuthProvider } from "../auth-provider";
import { useAuth } from "@/hooks/use-auth";

/**
 * keycloak-js throws on a second `init()` of one instance, and StrictMode runs
 * effects twice in development: the provider used to call `init()` once per
 * run, the second call threw, and the dev Manager never finished signing in.
 */
const init = vi.fn();
vi.mock("keycloak-js", () => ({
  default: class {
    token = "tok";
    idToken = "id";
    tokenParsed = { preferred_username: "p13-editor", realm_access: { roles: ["eddi-editor"] } };
    onTokenExpired: (() => void) | undefined;
    init(...args: unknown[]) {
      init(...args);
      if (init.mock.calls.length > 1) {
        return Promise.reject(new Error("A 'Keycloak' instance can only be initialized once."));
      }
      return Promise.resolve(true);
    }
    loadUserInfo() {
      return Promise.resolve({ preferred_username: "p13-editor" });
    }
    updateToken() {
      return Promise.resolve(true);
    }
    login() {}
    logout() {}
  },
}));

function Who() {
  const { authenticated, roles } = useAuth();
  return (
    <span data-testid="who">
      {authenticated ? "in" : "out"}:{roles.join(",")}
    </span>
  );
}

describe("AuthProvider under StrictMode", () => {
  it("initialises Keycloak once and signs in", async () => {
    render(
      <StrictMode>
        <AuthProvider configOverride={{ method: "keycloak", url: "http://kc", realm: "eddi", clientId: "eddi-frontend" }}>
          <Who />
        </AuthProvider>
      </StrictMode>,
    );
    expect(await screen.findByTestId("who")).toHaveTextContent("in:eddi-editor");
    expect(init).toHaveBeenCalledTimes(1);
  });
});
