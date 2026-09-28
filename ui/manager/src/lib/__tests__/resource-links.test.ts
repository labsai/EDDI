import { describe, expect, it } from "vitest";
import { chatLinkFor, isAgentUri, managerRouteFor } from "../resource-links";

const ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

describe("resource links", () => {
  it("routes each kind of resource to its page", () => {
    expect(managerRouteFor(`eddi://ai.labs.agent/agentstore/agents/${ID}?version=1`, ID)).toBe(`/manage/agentview/${ID}`);
    expect(managerRouteFor(`eddi://ai.labs.workflow/workflowstore/workflows/${ID}?version=2`, ID)).toBe(
      `/manage/workflowview/${ID}`
    );
    expect(managerRouteFor(`eddi://ai.labs.llm/llmstore/llms/${ID}?version=1`, ID)).toBe(`/manage/resources/llm/${ID}`);
  });

  it("returns no route rather than a wrong one", () => {
    expect(managerRouteFor(null, ID)).toBeNull();
    expect(managerRouteFor("not a uri", ID)).toBeNull();
    expect(managerRouteFor(`eddi://ai.labs.x/unknownstore/things/${ID}`, ID)).toBeNull();
  });

  it("recognises agents, the only resources people chat with", () => {
    expect(isAgentUri(`eddi://ai.labs.agent/agentstore/agents/${ID}?version=1`)).toBe(true);
    expect(isAgentUri(`eddi://ai.labs.llm/llmstore/llms/${ID}?version=1`)).toBe(false);
    expect(isAgentUri(undefined)).toBe(false);
  });

  it("builds the chat address on the given origin", () => {
    expect(chatLinkFor(ID, "https://eddi.example.com")).toBe(`https://eddi.example.com/chat/production/${ID}`);
  });
});
