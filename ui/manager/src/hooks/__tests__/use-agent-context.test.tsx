import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { useAgentContext } from "@/hooks/use-agent-context";
import { type ReactNode } from "react";

function createWrapper(search: string) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={[`/test${search}`]}>
        {children}
      </MemoryRouter>
    );
  };
}

describe("useAgentContext", () => {
  it("returns null when no search params are present", () => {
    const { result } = renderHook(() => useAgentContext(), {
      wrapper: createWrapper(""),
    });
    expect(result.current).toBeNull();
  });

  it("returns null when only agentId is present", () => {
    const { result } = renderHook(() => useAgentContext(), {
      wrapper: createWrapper("?agentId=a1"),
    });
    expect(result.current).toBeNull();
  });

  it("returns null when only agentVer is present", () => {
    const { result } = renderHook(() => useAgentContext(), {
      wrapper: createWrapper("?agentVer=3"),
    });
    expect(result.current).toBeNull();
  });

  it("returns null when agentVer is not a number", () => {
    const { result } = renderHook(() => useAgentContext(), {
      wrapper: createWrapper("?agentId=a1&agentVer=abc"),
    });
    expect(result.current).toBeNull();
  });

  it("returns AgentContext when both agentId and valid agentVer are present", () => {
    const { result } = renderHook(() => useAgentContext(), {
      wrapper: createWrapper("?agentId=agent-123&agentVer=5"),
    });
    expect(result.current).toEqual({
      agentId: "agent-123",
      agentVer: 5,
    });
  });

  it("parses agentVer as integer", () => {
    const { result } = renderHook(() => useAgentContext(), {
      wrapper: createWrapper("?agentId=a1&agentVer=10"),
    });
    expect(result.current?.agentVer).toBe(10);
    expect(typeof result.current?.agentVer).toBe("number");
  });
});
