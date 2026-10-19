import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OPERATOR_DESCRIPTOR_DESCRIPTION } from "@/lib/api/operator-marker";
import { buildToolApprovals } from "@/lib/operator/tool-scopes";

/**
 * Make the agent store answer the way it does for a real Platform Operator, so a
 * screen that is gated on `useOperatorTrust` shows its chat.
 *
 * The Manager only talks to the agent `platform.operator` names once its
 * descriptor carries the operator marker and every version of its document has a
 * verified approval gate. Register this AFTER `server.resetHandlers()` and before
 * a test's own overrides, which take precedence over it.
 *
 * Options turn one of the two checks off, to test the untrusted path.
 */
export function serveTrustedOperator(
  agentId: string,
  options: { marker?: boolean; gate?: boolean; version?: number } = {},
): void {
  const { marker = true, gate = true, version = 1 } = options;
  server.use(
    http.get(`*/agentstore/agents/${agentId}/currentversion`, () => HttpResponse.json(version)),
    http.get(`*/agentstore/agents/${agentId}`, () =>
      HttpResponse.json(
        gate
          ? { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", toolApprovals: buildToolApprovals() } }
          : {},
      ),
    ),
    http.get(`*/descriptorstore/descriptors/${agentId}`, ({ request }) =>
      HttpResponse.json({
        resource: `eddi://ai.labs.agent/agentstore/agents/${agentId}?version=${new URL(request.url).searchParams.get("version") ?? version}`,
        name: "EDDI Platform Operator",
        description: marker ? OPERATOR_DESCRIPTOR_DESCRIPTION : "An ordinary agent",
        createdOn: 0,
        lastModifiedOn: 0,
      }),
    ),
  );
}
