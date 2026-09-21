import { describe, it, expect } from "vitest";
import { getGroupTemplates, buildGroupFromTemplate } from "@/lib/group-templates";
import type { TFunction } from "i18next";

describe("group-templates", () => {
  const mockT = ((key: string) => `translated_${key}`) as TFunction;

  describe("getGroupTemplates", () => {
    it("returns all templates with correct structure", () => {
      const templates = getGroupTemplates(mockT);
      expect(templates.length).toBe(7);
      
      const advisoryBoard = templates.find(t => t.key === "advisory-board");
      expect(advisoryBoard).toBeDefined();
      expect(advisoryBoard?.name).toBe("translated_groupTemplates.advisoryBoard");
      expect(advisoryBoard?.style).toBe("ROUND_TABLE");
      expect(advisoryBoard?.roles.length).toBe(5);
    });

    it("Each template has name, description, style, roles", () => {
      const templates = getGroupTemplates(mockT);
      for (const t of templates) {
        expect(t.name).toBeDefined();
        expect(typeof t.name).toBe("string");
        expect(t.description).toBeDefined();
        expect(t.style).toBeDefined();
        expect(t.roles).toBeDefined();
        expect(Array.isArray(t.roles)).toBe(true);
      }
    });
  });

    it("ships a NEGOTIATION template so the style is reachable from every wizard", () => {
      const templates = getGroupTemplates(mockT);
      const negotiation = templates.find(t => t.key === "negotiation");
      expect(negotiation).toBeDefined();
      expect(negotiation?.style).toBe("NEGOTIATION");
      // Two parties to bargain, and rounds drive Bargaining's repeats.
      expect(negotiation?.roles.length).toBe(2);
      expect(negotiation?.maxRounds).toBeGreaterThan(1);
      expect(negotiation?.moderatorSuggested).toBe(true);
    });

  describe("buildGroupFromTemplate", () => {
    it("applyTemplate creates a proper config from a template", () => {
      const templates = getGroupTemplates(mockT);
      const template = templates.find(t => t.key === "peer-review")!;
      
      const config = buildGroupFromTemplate(template);
      
      expect(config.name).toBe("translated_groupTemplates.peerReview");
      expect(config.description).toBe("translated_groupTemplates.peerReviewDesc");
      expect(config.style).toBe("PEER_REVIEW");
      expect(config.maxRounds).toBe(1);
      expect(config.members.length).toBe(3);
      expect(config.members[0]!.displayName).toBe("translated_groupTemplates.roles.substanceReviewer");
      expect(config.members[0]!.role).toBe("Substance");
      expect(config.members[0]!.agentId).toBe("");
      expect(config.members[0]!.speakingOrder).toBe(1);
      expect(config.moderatorAgentId).toBe(null);
    });

    it("applies overrides correctly", () => {
      const templates = getGroupTemplates(mockT);
      const template = templates[0]!;
      
      const config = buildGroupFromTemplate(template, {
        name: "Custom Name",
        maxRounds: 5,
        moderatorAgentId: "mod-1"
      });
      
      expect(config.name).toBe("Custom Name");
      expect(config.maxRounds).toBe(5);
      expect(config.moderatorAgentId).toBe("mod-1");
      expect(config.style).toBe(template!.style);
    });
  });
});
