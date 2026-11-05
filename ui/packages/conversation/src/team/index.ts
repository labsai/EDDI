/* ──────────────────────────────────────────────
   @eddi/conversation/team — the team kind of conversation (frontend
   unification plan §3.2): the GroupDiscussion controller and the team
   components the Manager's group workspace and Workforce's board and
   history render. A separate entry from the package's main one, so the
   Chat UI (agent kind only) never resolves it. Router-free like the rest of
   the package: hosts pass ids and callbacks.
   ────────────────────────────────────────────── */

export * from "../controller/group-discussion";
