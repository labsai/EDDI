import { describe, it, expect } from "vitest";
import {
  RESOURCE_TYPE_ICONS,
  UNKNOWN_RESOURCE_TYPE_ICON,
  getResourceTypeIcon,
} from "../resource-type-icons";
import { RESOURCE_TYPES } from "../api/resources";

describe("RESOURCE_TYPE_ICONS", () => {
  it("gives every kind its own icon", () => {
    // Five pipeline step types once shared one page glyph, and RAG was drawn
    // as the dictionary's book. Two kinds with one icon cannot be told apart
    // in a pipeline, so a new entry must pick an icon no other kind uses.
    const byIcon = new Map<unknown, string>();
    for (const [kind, icon] of Object.entries(RESOURCE_TYPE_ICONS)) {
      expect(byIcon.get(icon), `${kind} reuses the icon of ${byIcon.get(icon)}`).toBeUndefined();
      byIcon.set(icon, kind);
    }
  });

  it("keeps the unknown-type icon distinct from every known kind", () => {
    expect(Object.values(RESOURCE_TYPE_ICONS)).not.toContain(UNKNOWN_RESOURCE_TYPE_ICON);
  });

  it("covers every resource type the Manager lists", () => {
    // The card grid used to carry its own map with six of the ten slugs, and
    // silently drew the other four with its fallback.
    for (const rt of RESOURCE_TYPES) {
      expect(getResourceTypeIcon(rt.slug), `${rt.slug} has no icon`).not.toBe(UNKNOWN_RESOURCE_TYPE_ICON);
    }
  });

  it("falls back for unknown, empty and inherited keys", () => {
    expect(getResourceTypeIcon("not-a-type")).toBe(UNKNOWN_RESOURCE_TYPE_ICON);
    expect(getResourceTypeIcon("")).toBe(UNKNOWN_RESOURCE_TYPE_ICON);
    expect(getResourceTypeIcon(undefined)).toBe(UNKNOWN_RESOURCE_TYPE_ICON);
    expect(getResourceTypeIcon("constructor")).toBe(UNKNOWN_RESOURCE_TYPE_ICON);
  });
});
