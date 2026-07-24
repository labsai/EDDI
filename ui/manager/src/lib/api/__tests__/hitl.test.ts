import { describe, it, expect, vi, beforeEach } from "vitest";
import { api } from "../../api-client";
import {
  resumeConversation,
  getApprovalStatus,
  listPendingApprovals,
  cancelConversation,
  approveGroupPhase,
  getGroupApprovalStatus,
  listAllGroupPendingApprovals,
  cancelGroupDiscussion,
  TOOL_SOURCES,
  MAX_NOTE_LENGTH,
  MAX_TOOL_CALL_NOTE_LENGTH,
  MAX_PAUSE_REASON_LENGTH,
  AMENDED_ARGS_MAX_BYTES,
  type HitlDecision,
  type GroupApprovalRequest,
  type ApprovalStatusSummary,
  type PendingApprovalSummary,
} from "../hitl";

vi.mock("../../api-client", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    getAuthHeader: vi.fn().mockReturnValue({}),
    getBaseUrl: vi.fn().mockReturnValue(window.location.origin),
  },
}));

describe("HITL API", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("resumeConversation", () => {
    it("calls POST /agents/{conversationId}/resume with decision payload", async () => {
      const conversationId = "conv-123";
      const decision: HitlDecision = {
        verdict: "APPROVED",
        note: "Looks good to execute",
        toolDecisions: {
          "call-1": {
            verdict: "APPROVED",
            amendedArguments: '{"param":"value"}',
          },
        },
      };

      vi.mocked(api.post).mockResolvedValue(undefined);

      await resumeConversation(conversationId, decision);

      expect(api.post).toHaveBeenCalledTimes(1);
      expect(api.post).toHaveBeenCalledWith(
        `/agents/${conversationId}/resume`,
        decision
      );
    });
  });

  describe("getApprovalStatus", () => {
    it("calls GET /agents/{conversationId}/approval-status?detail=summary", async () => {
      const conversationId = "conv-456";
      const mockStatus: ApprovalStatusSummary = {
        conversationId,
        state: "AWAITING_HUMAN",
        pausedAt: "2026-07-24T00:00:00Z",
        pauseReason: "Tool call requires manager sign-off",
        pauseDetails: {
          type: "TOOL_CALL",
          calls: [
            {
              callId: "call-1",
              toolName: "delete_database",
              source: "builtin",
              argsTruncated: false,
            },
          ],
          executedUngatedCalls: [],
          outcomeUnknown: [],
        },
      };

      vi.mocked(api.get).mockResolvedValue(mockStatus);

      const result = await getApprovalStatus(conversationId);

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith(
        `/agents/${conversationId}/approval-status?detail=summary`
      );
      expect(result).toEqual(mockStatus);
    });
  });

  describe("listPendingApprovals", () => {
    it("calls GET /agents/pending-approvals with default limit (200)", async () => {
      const mockApprovals: PendingApprovalSummary[] = [
        {
          conversationId: "conv-1",
          pausedAt: "2026-07-24T00:00:00Z",
          pauseReason: "Approval required",
        },
      ];

      vi.mocked(api.get).mockResolvedValue(mockApprovals);

      const result = await listPendingApprovals();

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith("/agents/pending-approvals?limit=200");
      expect(result).toEqual(mockApprovals);
    });

    it("calls GET /agents/pending-approvals with custom limit", async () => {
      vi.mocked(api.get).mockResolvedValue([]);

      const result = await listPendingApprovals(50);

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith("/agents/pending-approvals?limit=50");
      expect(result).toEqual([]);
    });
  });

  describe("cancelConversation", () => {
    it("calls POST /agents/{conversationId}/cancel", async () => {
      const conversationId = "conv-789";
      vi.mocked(api.post).mockResolvedValue(undefined);

      await cancelConversation(conversationId);

      expect(api.post).toHaveBeenCalledTimes(1);
      expect(api.post).toHaveBeenCalledWith(`/agents/${conversationId}/cancel`);
    });
  });

  describe("approveGroupPhase", () => {
    it("calls POST /groups/{groupId}/conversations/{gcId}/approve with request payload", async () => {
      const groupId = "grp-1";
      const gcId = "gc-100";
      const request: GroupApprovalRequest = {
        decision: {
          verdict: "APPROVED",
          note: "Group phase approved",
        },
        taskApprovals: {
          "task-1": "APPROVED",
          "task-2": "REJECTED",
        },
      };

      vi.mocked(api.post).mockResolvedValue(undefined);

      await approveGroupPhase(groupId, gcId, request);

      expect(api.post).toHaveBeenCalledTimes(1);
      expect(api.post).toHaveBeenCalledWith(
        `/groups/${groupId}/conversations/${gcId}/approve`,
        request
      );
    });
  });

  describe("getGroupApprovalStatus", () => {
    it("calls GET /groups/{groupId}/conversations/{gcId}/approval-status with default detail='summary'", async () => {
      const groupId = "grp-1";
      const gcId = "gc-200";
      const mockResponse = { state: "AWAITING_HUMAN", phaseIndex: 1 };

      vi.mocked(api.get).mockResolvedValue(mockResponse);

      const result = await getGroupApprovalStatus(groupId, gcId);

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith(
        `/groups/${groupId}/conversations/${gcId}/approval-status?detail=summary`
      );
      expect(result).toEqual(mockResponse);
    });

    it("calls GET /groups/{groupId}/conversations/{gcId}/approval-status with custom detail='full'", async () => {
      const groupId = "grp-1";
      const gcId = "gc-200";
      const mockResponse = { state: "AWAITING_HUMAN", phaseIndex: 1, fullDetails: {} };

      vi.mocked(api.get).mockResolvedValue(mockResponse);

      const result = await getGroupApprovalStatus(groupId, gcId, "full");

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith(
        `/groups/${groupId}/conversations/${gcId}/approval-status?detail=full`
      );
      expect(result).toEqual(mockResponse);
    });
  });

  describe("listAllGroupPendingApprovals", () => {
    it("calls GET /groups/pending-approvals with default limit (200)", async () => {
      const mockGroupApprovals: PendingApprovalSummary[] = [
        {
          conversationId: "gc-1",
          groupId: "grp-1",
          pausedAt: "2026-07-24T00:00:00Z",
        },
      ];

      vi.mocked(api.get).mockResolvedValue(mockGroupApprovals);

      const result = await listAllGroupPendingApprovals();

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith("/groups/pending-approvals?limit=200");
      expect(result).toEqual(mockGroupApprovals);
    });

    it("calls GET /groups/pending-approvals with custom limit", async () => {
      vi.mocked(api.get).mockResolvedValue([]);

      const result = await listAllGroupPendingApprovals(10);

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(api.get).toHaveBeenCalledWith("/groups/pending-approvals?limit=10");
      expect(result).toEqual([]);
    });
  });

  describe("cancelGroupDiscussion", () => {
    it("calls POST /groups/{groupId}/conversations/{gcId}/cancel", async () => {
      const groupId = "grp-1";
      const gcId = "gc-300";

      vi.mocked(api.post).mockResolvedValue(undefined);

      await cancelGroupDiscussion(groupId, gcId);

      expect(api.post).toHaveBeenCalledTimes(1);
      expect(api.post).toHaveBeenCalledWith(
        `/groups/${groupId}/conversations/${gcId}/cancel`
      );
    });
  });

  describe("Exported Constants", () => {
    it("exports field limit constants with expected values", () => {
      expect(MAX_NOTE_LENGTH).toBe(4096);
      expect(MAX_TOOL_CALL_NOTE_LENGTH).toBe(1024);
      expect(MAX_PAUSE_REASON_LENGTH).toBe(500);
      expect(AMENDED_ARGS_MAX_BYTES).toBe(32768);
    });

    it("exports TOOL_SOURCES array", () => {
      expect(TOOL_SOURCES).toEqual([
        "builtin",
        "http",
        "mcp",
        "a2a",
        "dynamic",
        "memory",
        "recall",
      ]);
    });
  });
});
