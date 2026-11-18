import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "@eddi/app-kit/components/auth/auth-provider";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import { onSignOut } from "@eddi/app-kit/lib/session-events";
import { clearWorkforceThreads, principalOf, threadsStorageKey } from "@eddi/app-kit/lib/workforce-threads-storage";

const kc = vi.hoisted(() => ({
  init: vi.fn(),
  logout: vi.fn(),
  login: vi.fn(),
  updateToken: vi.fn(),
}));

vi.mock("keycloak-js", () => ({
  default: class MockKeycloak {
    token = "tok";
    idToken = "id";
    tokenParsed = { sub: "sub-1", preferred_username: "alice" };
    onTokenExpired: (() => void) | null = null;
    init = kc.init;
    login = kc.login;
    logout = kc.logout;
    updateToken = kc.updateToken;
    loadUserInfo() {
      return Promise.resolve({});
    }
  },
}));

const config = { method: "keycloak" as const, url: "http://localhost:8180", realm: "eddi", clientId: "eddi-manager" };

function SignOut() {
  const { logout } = useAuth();
  return <button onClick={logout}>sign out</button>;
}

describe("AuthProvider — sign-out is the app's own", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    kc.init.mockResolvedValue(true);
    kc.updateToken.mockResolvedValue(true);
  });

  it.each([
    ["manager", "/manage"],
    ["workforce", "/workforce"],
  ] as const)("%s signs out back to %s, not to the other app", async (app, path) => {
    render(
      <AuthProvider configOverride={config} app={app}>
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(kc.logout).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}${path}` });
  });

  it("defaults to the Manager", async () => {
    render(
      <AuthProvider configOverride={config}>
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(kc.logout).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}/manage` });
  });

  it("sign-out removes the signing-out person's thread pointers (by token subject) and the unscoped legacy key, and only those", async () => {
    localStorage.setItem(threadsStorageKey("sub-1"), "[]");
    localStorage.setItem(threadsStorageKey("sub-2"), "[]"); // somebody else on this profile
    localStorage.setItem("workforce-threads", "[]");
    localStorage.setItem("eddi-theme", "dark");
    render(
      <AuthProvider configOverride={config} app="workforce">
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(localStorage.getItem(threadsStorageKey("sub-1"))).toBeNull();
    expect(localStorage.getItem(threadsStorageKey("sub-2"))).toBe("[]");
    expect(localStorage.getItem("workforce-threads")).toBeNull();
    expect(localStorage.getItem("eddi-theme")).toBe("dark");
  });

  it("tells the page's in-memory per-person state to go when the user signs out", async () => {
    const gone = vi.fn();
    const off = onSignOut(gone);
    render(
      <AuthProvider configOverride={config} app="workforce">
        <SignOut />
      </AuthProvider>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));
    expect(gone).toHaveBeenCalledTimes(1);
    off();
  });

  it("a failed Workforce sign-in does not call itself the Manager", async () => {
    kc.init.mockResolvedValue(false);
    render(
      <AuthProvider configOverride={config} app="workforce">
        <div />
      </AuthProvider>,
    );
    const error = await screen.findByTestId("auth-error");
    expect(error).toHaveTextContent(/Workforce could not complete sign-in/);
    expect(error).not.toHaveTextContent(/The Manager/);
  });
});

describe("thread pointers are per principal", () => {
  beforeEach(() => localStorage.clear());

  it("two people on one browser get two keys; no one gets the unscoped key", () => {
    expect(threadsStorageKey("alice")).not.toBe(threadsStorageKey("bob"));
    expect(threadsStorageKey(null)).toBe(threadsStorageKey("  "));
    expect(threadsStorageKey("alice")).not.toBe("workforce-threads");
  });

  it("the principal is the token's subject, the username only as a fallback", () => {
    expect(principalOf({ sub: "s-1", username: "alice" })).toBe("s-1");
    expect(principalOf({ username: "alice" })).toBe("alice");
    expect(principalOf({ sub: " ", username: "" })).toBeNull();
    expect(principalOf(null)).toBeNull();
  });

  it("clearing removes one person's key and the legacy key, nothing else", () => {
    localStorage.setItem(threadsStorageKey("bob"), "[]");
    localStorage.setItem(threadsStorageKey("carol"), "[]");
    localStorage.setItem("workforce-threads", "[]");
    localStorage.setItem("workforce-pins-bob:b1", "[]");
    clearWorkforceThreads("bob");
    expect(localStorage.getItem(threadsStorageKey("bob"))).toBeNull();
    expect(localStorage.getItem("workforce-threads")).toBeNull();
    expect(localStorage.getItem(threadsStorageKey("carol"))).toBe("[]");
    expect(localStorage.getItem("workforce-pins-bob:b1")).toBe("[]");
  });
});
