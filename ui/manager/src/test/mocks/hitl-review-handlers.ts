import { HttpResponse } from "msw";
import type { ApprovalStatusSummary, HitlDecision } from "@/lib/api/hitl";

/**
 * Edit-on-approve (`hitlConfig.review`): a RULE pause whose agent lets
 * approvers edit the answer. `approval-status` of {@link EDITABLE_CONVERSATION_ID}
 * carries `pauseDetails.editableAnswer` and a reason-code vocabulary; its
 * `resume` records the last decision and the `X-EDDI-Client` header so tests can
 * assert what the Manager sent.
 */

export const EDITABLE_CONVERSATION_ID = "conv-editable-1";

export const EDITABLE_PAUSED_AT = "2026-11-02T10:00:00.000Z";

/** The last decision the editable conversation's resume received. */
export const lastEditableResume: { decision: HitlDecision | null; client: string | null } = {
  decision: null,
  client: null,
};

export function editableApprovalStatus(): ApprovalStatusSummary {
  return {
    conversationId: EDITABLE_CONVERSATION_ID,
    state: "AWAITING_HUMAN",
    pausedAt: EDITABLE_PAUSED_AT,
    pauseId: String(Date.parse(EDITABLE_PAUSED_AT)),
    pauseReason: "Check the reply before it is posted",
    pauseDetails: {
      type: "RULE",
      reason: "Check the reply before it is posted",
      actions: ["reply"],
      approvalCategory: "github.reply",
      reasonCodes: [
        { code: "FALSE_CLAIM", label: "States something untrue" },
        { code: "TONE", label: "Wrong tone or style" },
      ],
      editableAnswer: { draft: "The release is Friday.", format: "text", maxChars: 40 },
    },
  };
}

/**
 * A TOOL_CALL pause shaped like `RestAgentEngine.buildToolCallPauseDetails`:
 * a generated (OpenAPI) tool whose body is JSON inside a JSON string, with a
 * redacted secret, and an MCP tool. For the readable tool view and
 * edit-on-approve of gated calls.
 */
export const TOOL_CALL_CONVERSATION_ID = "conv-toolcall-1";

export function toolCallApprovalStatus(): ApprovalStatusSummary {
  return {
    conversationId: TOOL_CALL_CONVERSATION_ID,
    state: "AWAITING_HUMAN",
    pausedAt: EDITABLE_PAUSED_AT,
    pauseId: String(Date.parse(EDITABLE_PAUSED_AT)),
    pauseReason: "Approval required for createAgent, add_issue_comment",
    pauseDetails: {
      type: "TOOL_CALL",
      calls: [
        {
          callId: "call_create",
          toolName: "createAgent",
          source: "http",
          arguments: JSON.stringify({
            requestBody: JSON.stringify({ agentName: "Support", description: "Answers billing questions", apiKey: "<REDACTED>" }),
          }),
          argsTruncated: false,
          gateReason: "http.post:*",
          requestPinned: false,
          requestPreview: null,
          approvalCategory: "tool:http.post:*",
        },
        {
          callId: "call_comment",
          toolName: "add_issue_comment",
          source: "mcp",
          arguments: JSON.stringify({ owner: "labsai", repo: "EDDI", issue_number: 1264, body: "Thanks, fixed in 6.7.0." }),
          argsTruncated: false,
          gateReason: "mcp:add_issue_comment",
          requestPinned: false,
          requestPreview: null,
          approvalCategory: "github.reply",
        },
      ],
      executedUngatedCalls: [],
      outcomeUnknown: [],
      reasonCodes: [
        { code: "FALSE_CLAIM", label: "States something untrue" },
        { code: "SCOPE", label: "Out of scope" },
      ],
    },
  };
}

/**
 * Answers the generic approval-status / resume handlers of `handlers.ts` for
 * {@link EDITABLE_CONVERSATION_ID} and {@link TOOL_CALL_CONVERSATION_ID}, or
 * returns undefined for any other id. Not handlers of its own: the OpenAPI
 * contract test allows one handler per endpoint, so the generic ones delegate here.
 */
export function editableApprovalStatusFor(conversationId: unknown): Response | undefined {
  if (conversationId === TOOL_CALL_CONVERSATION_ID) return HttpResponse.json(toolCallApprovalStatus());
  return conversationId === EDITABLE_CONVERSATION_ID ? HttpResponse.json(editableApprovalStatus()) : undefined;
}

export async function editableResumeFor(conversationId: unknown, request: Request): Promise<Response | undefined> {
  if (conversationId !== EDITABLE_CONVERSATION_ID) return undefined;
  lastEditableResume.decision = (await request.clone().json().catch(() => null)) as HitlDecision | null;
  lastEditableResume.client = request.headers.get("X-EDDI-Client");
  return new HttpResponse(null, { status: 200 });
}
