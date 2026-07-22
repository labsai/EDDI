import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useTemplates } from "@/hooks/use-templates";

describe("useTemplates", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("Hook returns templates array", () => {
    const { result } = renderHook(() => useTemplates());
    expect(result.current.templates).toEqual([]);
  });

  it("Templates have expected structure and can be saved, retrieved, and deleted", () => {
    const { result } = renderHook(() => useTemplates());
    
    let savedId: string;
    
    act(() => {
      const newTmpl = result.current.saveTemplate({
        name: "Test Template",
        description: "Test description",
        style: "DEBATE",
        members: [{ displayName: "Member 1", role: "Role 1" }],
        maxRounds: 2
      });
      savedId = newTmpl.id;
    });

    expect(result.current.templates.length).toBe(1);
    
    const saved = result.current.getTemplate(savedId!);
    expect(saved).toBeDefined();
    expect(saved?.id).toBe(savedId!);
    expect(saved?.name).toBe("Test Template");
    expect(saved?.description).toBe("Test description");
    expect(saved?.style).toBe("DEBATE");
    expect(saved?.members).toEqual([{ displayName: "Member 1", role: "Role 1" }]);
    expect(saved?.maxRounds).toBe(2);
    expect(typeof saved?.createdAt).toBe("string");

    // Persisted properly
    const { result: result2 } = renderHook(() => useTemplates());
    expect(result2.current.templates.length).toBe(1);
    expect(result2.current.templates[0].id).toBe(savedId!);

    // Delete
    act(() => {
      result.current.deleteTemplate(savedId!);
    });

    expect(result.current.templates.length).toBe(0);
    expect(result.current.getTemplate(savedId!)).toBeUndefined();
  });
  
  it("Handles corrupt localStorage gracefully", () => {
    localStorage.setItem("workforce-templates", "invalid json");
    const { result } = renderHook(() => useTemplates());
    expect(result.current.templates).toEqual([]);
  });
});
