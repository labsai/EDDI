import { describe, it, expect, vi } from "vitest";
import { getGroupTemplates, buildGroupFromTemplate } from "../group-templates";
import type { TFunction } from "i18next";

// Minimal mock t() that returns the key (mirrors react-i18next test behaviour)
const mockT = ((key: string) => key) as unknown as TFunction;

describe("getGroupTemplates", () => {
  it("returns 5 templates", () => {
    const templates = getGroupTemplates(mockT);
    expect(templates).toHaveLength(5);
  });

  it("each template has required fields", () => {
    const templates = getGroupTemplates(mockT);
    for (const tmpl of templates) {
      expect(tmpl.key).toBeTruthy();
      expect(tmpl.name).toBeTruthy();
      expect(tmpl.description).toBeTruthy();
      expect(tmpl.icon).toBeTruthy();
      expect(tmpl.style).toBeTruthy();
      expect(tmpl.maxRounds).toBeGreaterThan(0);
      expect(tmpl.roles.length).toBeGreaterThanOrEqual(2);
      expect(typeof tmpl.moderatorSuggested).toBe("boolean");
    }
  });

  it("template names come from i18n keys", () => {
    const templates = getGroupTemplates(mockT);
    // Since mockT returns the key, template names should be i18n key paths
    expect(templates[0]!.name).toBe("groupTemplates.advisoryBoard");
    expect(templates[1]!.name).toBe("groupTemplates.codeReview");
    expect(templates[2]!.name).toBe("groupTemplates.riskAssessment");
    expect(templates[3]!.name).toBe("groupTemplates.forecasting");
    expect(templates[4]!.name).toBe("groupTemplates.proCon");
  });

  it("role displayNames come from i18n keys", () => {
    const templates = getGroupTemplates(mockT);
    const advisoryRoles = templates[0]!.roles;
    expect(advisoryRoles[0]!.displayName).toBe("groupTemplates.roles.marketingExpert");
    expect(advisoryRoles[1]!.displayName).toBe("groupTemplates.roles.techLead");
  });

  it("calls t() for each translatable string", () => {
    const spyT = vi.fn((key: string) => key) as unknown as TFunction;
    getGroupTemplates(spyT);
    // Each template: name + description + each role displayName
    // advisory: 2 + 5, code: 2 + 3, risk: 2 + 3, forecast: 2 + 4, debate: 2 + 4 = 29
    expect(spyT).toHaveBeenCalledTimes(29);
  });

  it("templates have unique keys", () => {
    const templates = getGroupTemplates(mockT);
    const keys = templates.map((t) => t.key);
    expect(new Set(keys).size).toBe(keys.length);
  });
});

describe("buildGroupFromTemplate", () => {
  it("builds config from template with correct defaults", () => {
    const template = getGroupTemplates(mockT)[0]!; // advisory board
    const config = buildGroupFromTemplate(template);

    expect(config.name).toBe(template.name);
    expect(config.description).toBe(template.description);
    expect(config.style).toBe("ROUND_TABLE");
    expect(config.maxRounds).toBe(2);
    expect(config.members).toHaveLength(5);
    expect(config.moderatorAgentId).toBeNull();
    expect(config.protocol?.agentTimeoutSeconds).toBe(60);
  });

  it("respects overrides", () => {
    const template = getGroupTemplates(mockT)[0]!;
    const config = buildGroupFromTemplate(template, {
      name: "Custom Name",
      maxRounds: 5,
      moderatorAgentId: "mod-123",
    });

    expect(config.name).toBe("Custom Name");
    expect(config.maxRounds).toBe(5);
    expect(config.moderatorAgentId).toBe("mod-123");
  });

  it("members have empty agentId placeholders", () => {
    const template = getGroupTemplates(mockT)[0]!;
    const config = buildGroupFromTemplate(template);

    for (const member of config.members) {
      expect(member.agentId).toBe("");
      expect(member.memberType).toBe("AGENT");
    }
  });

  it("members have sequential speaking order", () => {
    const template = getGroupTemplates(mockT)[0]!;
    const config = buildGroupFromTemplate(template);

    config.members.forEach((m, i) => {
      expect(m.speakingOrder).toBe(i + 1);
    });
  });
});
