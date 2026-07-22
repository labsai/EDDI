import { describe, it, expect } from "vitest";
import { stateLabel, styleLabel } from "../filter-utils";
import type { GroupConversationState, DiscussionStyle } from "@/lib/api/groups";

describe("filter-utils", () => {
  describe("stateLabel", () => {
    it("returns correct labels for all 8 states", () => {
      expect(stateLabel("COMPLETED")).toBe("Completed");
      expect(stateLabel("FAILED")).toBe("Failed");
      expect(stateLabel("IN_PROGRESS")).toBe("In Progress");
      expect(stateLabel("SYNTHESIZING")).toBe("Synthesizing");
      expect(stateLabel("CREATED")).toBe("Created");
      expect(stateLabel("CANCELLED")).toBe("Cancelled");
      expect(stateLabel("AWAITING_APPROVAL")).toBe("Pending");
      expect(stateLabel("CLOSED")).toBe("Closed");
    });

    it("returns raw value for unknown state", () => {
      expect(stateLabel("UNKNOWN_STATE" as GroupConversationState)).toBe("UNKNOWN_STATE");
    });
  });

  describe("styleLabel", () => {
    it("returns correct labels for known styles", () => {
      expect(styleLabel("DEBATE")).toBe("Structured Deliberation");
      expect(styleLabel("ROUND_TABLE")).toBe("Collaborative Council");
      expect(styleLabel("PEER_REVIEW")).toBe("Quality Review");
      expect(styleLabel("DEVIL_ADVOCATE")).toBe("Stress Test");
      expect(styleLabel("DELPHI")).toBe("Expert Forecast");
      expect(styleLabel("TASK_FORCE")).toBe("Operational Task Force");
      expect(styleLabel("CUSTOM")).toBe("Custom Framework");
    });

    it("returns raw value for unknown style", () => {
      expect(styleLabel("UNKNOWN_STYLE" as DiscussionStyle)).toBe("UNKNOWN_STYLE");
    });
  });
});
