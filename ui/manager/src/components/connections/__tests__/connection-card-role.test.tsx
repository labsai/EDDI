import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
import { ConnectionCard } from "@/components/connections/connection-card";
import type { EnrichedConnectionDescriptor } from "@eddi/app-kit/lib/api/connections";

const connection = { id: "c1", version: 1, connectionName: "jira", authType: "STATIC", binding: "SHARED", origins: ["https://jira.example.com"] } as unknown as EnrichedConnectionDescriptor;

const renderAsRole = (roles: string[]) =>
  renderWithProviders(
    <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles }}>
      <ConnectionCard connection={connection} onDelete={vi.fn()} onDuplicate={vi.fn()} />
    </AuthContext.Provider>,
  );

describe("ConnectionCard — change actions are admin-only", () => {
  it("hides delete and duplicate for an editor, who may only read connections", () => {
    renderAsRole(["eddi-editor"]);
    expect(screen.getByTestId("connection-card-c1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /duplicate/i })).not.toBeInTheDocument();
  });

  it("shows them to an administrator", () => {
    renderAsRole(["eddi-admin"]);
    expect(screen.getByRole("button", { name: /delete/i })).toBeInTheDocument();
  });

  it("shows them with auth off", () => {
    renderWithProviders(<ConnectionCard connection={connection} onDelete={vi.fn()} onDuplicate={vi.fn()} />);
    expect(screen.getByRole("button", { name: /delete/i })).toBeInTheDocument();
  });
});
