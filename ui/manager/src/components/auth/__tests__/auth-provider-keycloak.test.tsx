import { StrictMode } from "react";
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../auth-provider";
import { useAuth } from "@/hooks/use-auth";
import { api } from "@/lib/api-client";
import { userEvent } from "@/test/test-utils";

/**
 * The Keycloak half of AuthProvider, against a controllable fake.
 *
 * Findings covered: StrictMode could never authenticate (double init), a failed
 * init rendered the app unauthenticated with no way to sign in, roles were read
 * once and never refreshed, and nothing user-scoped was cleared at logout.
 */

const kc = vi.hoisted(() => ({
  instances: [] as unknown[],
  initImpl: null as null | (() => Promise<boolean>),
}));

vi.mock("keycloak-js", () => ({
  default: class FakeKeycloak {
    didInitialize = false;
    token: string | undefined = undefined;
    refreshToken: string | undefined = undefined;
    idToken: string | undefined = undefined;
    tokenParsed: Record<string, unknown> = {};
    onTokenExpired: (() => void) | undefined = undefined;
    initCalls = 0;
    logout = vi.fn(async () => {});
    login = vi.fn(async () => {});
    updateToken = vi.fn(async () => false);
    constructor() {
      kc.instances.push(this);
    }
    async init() {
      this.initCalls++;
      // Mirrors keycloak-js: a second init on one instance throws.
      if (this.didInitialize) {
        throw new Error("A 'Keycloak' instance can only be initialized once.");
      }
      this.didInitialize = true;
      if (kc.initImpl) return kc.initImpl();
      this.token = "token-1";
      this.refreshToken = "refresh-1";
      this.tokenParsed = {
        preferred_username: "alice",
        realm_access: { roles: ["eddi-viewer"] },
      };
      return true;
    }
    async loadUserInfo() {
      return { preferred_username: "alice" };
    }
  },
}));

type FakeInstance = {
  initCalls: number;
  token?: string;
  tokenParsed: Record<string, unknown>;
  onTokenExpired?: () => void;
  updateToken: ReturnType<typeof vi.fn>;
  logout: ReturnType<typeof vi.fn>;
};

function lastInstance(): FakeInstance {
  // StrictMode runs the useState initializer twice and keeps one result; the
  // kept instance is the one that was initialised.
  return (kc.instances as FakeInstance[]).find((i) => i.initCalls > 0)!;
}

function Probe() {
  const { authenticated, roles, user, logout } = useAuth();
  return (
    <div>
      <span data-testid="status">{authenticated ? "in" : "out"}</span>
      <span data-testid="user">{user?.username ?? ""}</span>
      <span data-testid="roles">{roles.join(",")}</span>
      <button data-testid="logout" onClick={logout}>
        logout
      </button>
    </div>
  );
}

const CONFIG = {
  method: "keycloak" as const,
  url: "http://localhost:8180",
  realm: "eddi",
  clientId: "eddi-manager",
};

function renderStrict() {
  return render(
    <StrictMode>
      <AuthProvider configOverride={CONFIG}>
        <Probe />
      </AuthProvider>
    </StrictMode>,
  );
}

describe("AuthProvider (Keycloak)", () => {
  afterEach(() => {
    kc.instances.length = 0;
    kc.initImpl = null;
    api.setTokenRefresher(null);
    api.clearAuthToken();
  });

  it("signs in under React StrictMode — init runs once, not twice", async () => {
    renderStrict();
    expect(await screen.findByTestId("status")).toHaveTextContent("in");
    expect(screen.getByTestId("user")).toHaveTextContent("alice");
    // Every instance StrictMode created was initialised at most once.
    for (const instance of kc.instances as FakeInstance[]) {
      expect(instance.initCalls).toBeLessThanOrEqual(1);
    }
    expect(api.getAuthHeader()).toEqual({ Authorization: "Bearer token-1" });
  });

  it("shows a sign-in error instead of an unauthenticated app when init fails", async () => {
    kc.initImpl = () => Promise.reject(new Error("Failed to fetch"));
    vi.spyOn(console, "error").mockImplementation(() => {});
    renderStrict();
    expect(await screen.findByTestId("auth-init-failed")).toBeInTheDocument();
    expect(screen.getByTestId("auth-retry")).toBeInTheDocument();
    expect(screen.queryByTestId("status")).not.toBeInTheDocument();
  });

  it("re-reads realm roles when the token is refreshed", async () => {
    renderStrict();
    expect(await screen.findByTestId("roles")).toHaveTextContent("eddi-viewer");

    const instance = lastInstance();
    instance.updateToken.mockImplementation(async () => {
      instance.token = "token-2";
      instance.tokenParsed = {
        ...instance.tokenParsed,
        realm_access: { roles: ["eddi-viewer", "eddi-admin"] },
      };
      return true;
    });
    await act(async () => {
      instance.onTokenExpired?.();
    });

    expect(await screen.findByText("eddi-viewer,eddi-admin")).toBeInTheDocument();
    expect(api.getAuthHeader()).toEqual({ Authorization: "Bearer token-2" });
  });

  it("clears this user's browser-held Workforce threads at logout", async () => {
    localStorage.setItem("workforce-threads:alice", "[]");
    localStorage.setItem("workforce-threads", "[]");
    localStorage.setItem("workforce-templates:alice", "[]");
    const user = userEvent.setup();
    renderStrict();
    await screen.findByTestId("status");

    await user.click(screen.getByTestId("logout"));

    expect(localStorage.getItem("workforce-threads:alice")).toBeNull();
    expect(localStorage.getItem("workforce-threads")).toBeNull();
    // Templates exist only in this browser; they are per-user, not wiped.
    expect(localStorage.getItem("workforce-templates:alice")).toBe("[]");
    expect(lastInstance().logout).toHaveBeenCalled();
    expect(api.getAuthHeader()).toEqual({});
  });
});
