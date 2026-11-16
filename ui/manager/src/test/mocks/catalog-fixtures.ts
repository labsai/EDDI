// No imports on purpose: the Playwright specs run in Node and import these
// fixtures, and handlers.ts pulls in modules that read `window` at load time.

/**
 * The end-user catalog as the backend would answer it (`IRestCatalog`): only
 * agents and groups that are LISTED and that the caller may use. The mock
 * backend has more agents than this — the Platform Operator among them in some
 * tests — and none of the unlisted ones belong here; the Workforce E2E spec
 * running as `eddi-user` checks the screen shows exactly these.
 */
export const MOCK_CATALOG_AGENTS = [
  {
    agentId: "aaaaaaaaaaaaaaaaaaaaaa01",
    name: "Support Assistant",
    description: "Answers questions about orders and returns",
    reviewNotice: null,
    rating: null,
    callerLevel: "USE",
  },
  {
    agentId: "aaaaaaaaaaaaaaaaaaaaaa02",
    name: "Travel Planner",
    description: "Plans trips within the travel policy",
    reviewNotice: null,
    rating: null,
    callerLevel: "USE",
  },
] as const;

export const MOCK_CATALOG_GROUPS = [
  {
    groupId: "grp1",
    version: 1,
    name: "Product Review Panel",
    description: "Peer-review discussion for product decisions",
    style: "PEER_REVIEW",
    members: [
      { agentId: "aaaaaaaaaaaaaaaaaaaaaa01", displayName: "Support Assistant", role: null, memberType: "AGENT", speakingOrder: 1 },
      { agentId: "aaaaaaaaaaaaaaaaaaaaaa02", displayName: "Travel Planner", role: null, memberType: "AGENT", speakingOrder: 2 },
    ],
    callerLevel: "USE",
  },
] as const;
