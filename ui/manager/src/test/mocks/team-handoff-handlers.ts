import { http, HttpResponse } from "msw";

/**
 * Cross-team handoff intake (docs/team-handoff.md): the decisions a reviewer
 * makes on a group's workspace. The inbox itself is part of the workspace
 * document (`handoffs`), which the generic workspace handler serves empty;
 * a test that wants items overrides that one handler with `server.use`.
 *
 * Shapes follow the backend: accept answers `{message, task, origin}`, decline
 * `{message, task: null, origin}`, delete 204; an unknown id is a 404 with
 * `{error}`.
 */
export const teamHandoffHandlers = [
  http.get("*/groupstore/groups/:groupId/workspace/handoffs", () =>
    HttpResponse.json({ inbox: [], origins: {} }),
  ),

  http.post("*/groupstore/groups/:groupId/workspace/handoffs/:handoffId/accept", async ({ params }) =>
    HttpResponse.json({
      message: "Accepted onto the backlog.",
      task: { id: `task-${String(params.handoffId)}`, status: "PENDING" },
      origin: null,
    }),
  ),

  http.post("*/groupstore/groups/:groupId/workspace/handoffs/:handoffId/decline", async () =>
    HttpResponse.json({ message: "Declined.", task: null, origin: null }),
  ),

  http.delete("*/groupstore/groups/:groupId/workspace/backlog/:taskId", () => new HttpResponse(null, { status: 204 })),
];
