import { describe, expect, it } from "vitest";
import { renderHook } from "@testing-library/react";
import { type ReactNode } from "react";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { useAuth, useHasRole } from "@/hooks/use-auth";

function createAuthWrapper(value: AuthContextValue) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
  };
}

const GUEST_AUTH: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: [],
  method: "none",
  login: () => {},
  logout: () => {},
};

const KEYCLOAK_AUTH: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: {
    username: "admin",
    firstName: "Admin",
    lastName: "User",
    email: "admin@test.com",
    fullName: "Admin User",
  },
  roles: ["admin", "editor"],
  method: "keycloak",
  login: () => {},
  logout: () => {},
};

describe("useAuth", () => {
  it("returns guest context when no auth provider", () => {
    const { result } = renderHook(() => useAuth(), {
      wrapper: createAuthWrapper(GUEST_AUTH),
    });
    expect(result.current.authenticated).toBe(true);
    expect(result.current.method).toBe("none");
    expect(result.current.user).toBeNull();
    expect(result.current.roles).toEqual([]);
  });

  it("returns keycloak context when authenticated", () => {
    const { result } = renderHook(() => useAuth(), {
      wrapper: createAuthWrapper(KEYCLOAK_AUTH),
    });
    expect(result.current.authenticated).toBe(true);
    expect(result.current.method).toBe("keycloak");
    expect(result.current.user).toEqual(
      expect.objectContaining({ username: "admin" })
    );
    expect(result.current.roles).toContain("admin");
  });

  it("exposes login/logout functions", () => {
    const { result } = renderHook(() => useAuth(), {
      wrapper: createAuthWrapper(GUEST_AUTH),
    });
    expect(typeof result.current.login).toBe("function");
    expect(typeof result.current.logout).toBe("function");
  });
});

describe("useHasRole", () => {
  it("returns true for any role when auth is disabled (method=none)", () => {
    const { result } = renderHook(() => useHasRole("admin"), {
      wrapper: createAuthWrapper(GUEST_AUTH),
    });
    expect(result.current).toBe(true);
  });

  it("returns true when user has the specified role", () => {
    const { result } = renderHook(() => useHasRole("admin"), {
      wrapper: createAuthWrapper(KEYCLOAK_AUTH),
    });
    expect(result.current).toBe(true);
  });

  it("returns false when user does not have the role", () => {
    const { result } = renderHook(() => useHasRole("superadmin"), {
      wrapper: createAuthWrapper(KEYCLOAK_AUTH),
    });
    expect(result.current).toBe(false);
  });

  it("returns true for another existing role", () => {
    const { result } = renderHook(() => useHasRole("editor"), {
      wrapper: createAuthWrapper(KEYCLOAK_AUTH),
    });
    expect(result.current).toBe(true);
  });
});
