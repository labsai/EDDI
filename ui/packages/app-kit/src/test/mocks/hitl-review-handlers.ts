import { HttpResponse } from "msw";
import type { ApprovalStatusSummary, HitlDecision } from "../../lib/api/hitl";

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
 * Answers the generic approval-status / resume handlers of `handlers.ts` for
 * {@link EDITABLE_CONVERSATION_ID}, or returns undefined for any other id. Not
 * handlers of its own: the OpenAPI contract test allows one handler per
 * endpoint, so the generic ones delegate here.
 */
export function editableApprovalStatusFor(conversationId: unknown): Response | undefined {
  return conversationId === EDITABLE_CONVERSATION_ID ? HttpResponse.json(editableApprovalStatus()) : undefined;
}

export async function editableResumeFor(conversationId: unknown, request: Request): Promise<Response | undefined> {
  if (conversationId !== EDITABLE_CONVERSATION_ID) return undefined;
  lastEditableResume.decision = (await request.clone().json().catch(() => null)) as HitlDecision | null;
  lastEditableResume.client = request.headers.get("X-EDDI-Client");
  return new HttpResponse(null, { status: 200 });
}
