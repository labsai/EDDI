import { describe, it, expect } from "vitest";
import {
  isStructuredEntryType,
  parseBargainPayload,
  parseBidPayload,
  parseRetroPayload,
  parseStructuredPayload,
  parseVotePayload,
} from "@/lib/group-payloads";

/**
 * The fixtures here are the contracts EDDI's own engines write, copied from the
 * prompts that produce them — `VoteTallyEngine`, `TaskBidEngine`,
 * `NegotiationEngine` and `RetroEngine`. When one of those changes, this file
 * should fail before a user sees a JSON blob in a transcript.
 */
describe("group payload readers", () => {
  describe("VOTE", () => {
    it("reads a single-choice ballot", () => {
      expect(
        parseVotePayload('{"vote": "Option A", "confidence": 0.8, "statement": "Cheaper."}'),
      ).toEqual({
        kind: "VOTE",
        options: ["Option A"],
        confidence: 0.8,
        statement: "Cheaper.",
      });
    });

    it("reads an APPROVAL ballot's several options", () => {
      expect(parseVotePayload('{"votes": ["A", "B"], "confidence": 0.5}')?.options).toEqual([
        "A",
        "B",
      ]);
    });

    it("does not repeat an option present in both `votes` and `vote`", () => {
      expect(parseVotePayload('{"votes": ["A"], "vote": "A"}')?.options).toEqual(["A"]);
    });

    it("clamps confidence to 0..1, as the backend does", () => {
      expect(parseVotePayload('{"vote": "A", "confidence": 4}')?.confidence).toBe(1);
      expect(parseVotePayload('{"vote": "A", "confidence": -2}')?.confidence).toBe(0);
    });

    it("reports a missing confidence rather than inventing one", () => {
      expect(parseVotePayload('{"vote": "A"}')?.confidence).toBeNull();
    });

    it("keeps a statement-only ballot, so the reasoning is not lost", () => {
      const payload = parseVotePayload('{"statement": "I abstain."}');
      expect(payload?.options).toEqual([]);
      expect(payload?.statement).toBe("I abstain.");
    });

    it("declines an object that is neither an option nor a statement", () => {
      expect(parseVotePayload('{"note": "hello"}')).toBeNull();
    });
  });

  describe("BID", () => {
    const BID =
      '{"bids": [{"subject": "Draft spec", "confidence": 0.9, "estimatedComplexity": "M", "rationale": "I own the API."}]}';

    it("reads a bid sheet", () => {
      expect(parseBidPayload(BID)).toEqual({
        kind: "BID",
        bids: [
          {
            subject: "Draft spec",
            confidence: 0.9,
            estimatedComplexity: "M",
            rationale: "I own the API.",
          },
        ],
      });
    });

    it("drops a bid with no subject, which the backend cannot match to a task", () => {
      expect(parseBidPayload('{"bids": [{"confidence": 0.9}]}')?.bids).toEqual([]);
    });

    it("keeps an empty bid sheet — bidding on nothing is an answer", () => {
      expect(parseBidPayload('{"bids": []}')).toEqual({ kind: "BID", bids: [] });
    });

    it("declines an object with no `bids` key at all", () => {
      expect(parseBidPayload('{"tasks": []}')).toBeNull();
    });
  });

  describe("BARGAIN", () => {
    it("reads an accept", () => {
      expect(parseBargainPayload('{"accept": "p-1"}')).toEqual({
        kind: "BARGAIN",
        accept: "p-1",
        proposalTerms: null,
        concessions: [],
      });
    });

    it("reads a counter-proposal's terms out of its nested object", () => {
      expect(
        parseBargainPayload('{"proposal": {"terms": "90 up front"}}')?.proposalTerms,
      ).toBe("90 up front");
    });

    it("drops a concession that names no return, as the backend does", () => {
      const payload = parseBargainPayload(
        '{"concessions": [{"gaveUp": "price"}, {"gaveUp": "price", "inReturnFor": "volume"}]}',
      );
      expect(payload?.concessions).toEqual([{ gaveUp: "price", inReturnFor: "volume" }]);
    });

    it("declines a turn that carries no move at all", () => {
      expect(parseBargainPayload('{"comment": "thinking"}')).toBeNull();
    });
  });

  describe("RETRO", () => {
    it("reads lessons with their context", () => {
      expect(
        parseRetroPayload('{"lessons": [{"lesson": "Timebox it", "context": "phase 2"}]}'),
      ).toEqual({
        kind: "RETRO",
        lessons: [{ lesson: "Timebox it", context: "phase 2" }],
      });
    });

    it("keeps a lesson with no context", () => {
      expect(parseRetroPayload('{"lessons": [{"lesson": "Timebox it"}]}')?.lessons).toEqual([
        { lesson: "Timebox it", context: null },
      ]);
    });

    it("declines a harvest whose lessons are all unusable", () => {
      expect(parseRetroPayload('{"lessons": [{"context": "no lesson"}]}')).toBeNull();
    });
  });

  describe("lenient reading", () => {
    it("finds the contract inside a markdown fence, tier 2 like the backend", () => {
      const fenced = 'Here is my ballot:\n```json\n{"vote": "A"}\n```\nThanks!';
      expect(parseVotePayload(fenced)?.options).toEqual(["A"]);
    });

    it("does not truncate on a brace inside a quoted value", () => {
      // `lastIndexOf("}")` would work here but the naive `indexOf("}")` would
      // cut mid-string; a balanced scan has to be string-aware either way.
      const body = '{"vote": "A", "statement": "we agreed } then moved on"}';
      expect(parseVotePayload(body)?.statement).toBe("we agreed } then moved on");
    });

    it("survives an escaped quote inside a value", () => {
      const body = '{"vote": "A", "statement": "he said \\"yes\\" once"}';
      expect(parseVotePayload(body)?.statement).toBe('he said "yes" once');
    });

    it("gives up on prose, so the caller falls back to rendering it as prose", () => {
      expect(parseVotePayload("I vote for option A.")).toBeNull();
      expect(parseBidPayload("")).toBeNull();
      expect(parseRetroPayload(null)).toBeNull();
    });
  });

  describe("dispatch", () => {
    it("routes each entry type to its own reader", () => {
      expect(parseStructuredPayload("VOTE", '{"vote": "A"}')?.kind).toBe("VOTE");
      expect(parseStructuredPayload("BID", '{"bids": []}')?.kind).toBe("BID");
      expect(parseStructuredPayload("BARGAIN", '{"accept": "p"}')?.kind).toBe("BARGAIN");
      expect(parseStructuredPayload("RETRO", '{"lessons": [{"lesson": "x"}]}')?.kind).toBe(
        "RETRO",
      );
    });

    it("leaves a prose entry type alone even when its body happens to be JSON", () => {
      // An OPINION whose body is JSON is not a ballot. Reading it as one would
      // put a typed card on a turn that never agreed to a contract.
      expect(parseStructuredPayload("OPINION", '{"vote": "A"}')).toBeNull();
      expect(parseStructuredPayload("SYNTHESIS", '{"winner": "pro"}')).toBeNull();
    });

    it("knows which entry types carry a contract", () => {
      expect(isStructuredEntryType("VOTE")).toBe(true);
      expect(isStructuredEntryType("BID")).toBe(true);
      expect(isStructuredEntryType("OPINION")).toBe(false);
      expect(isStructuredEntryType(null)).toBe(false);
    });
  });
});
