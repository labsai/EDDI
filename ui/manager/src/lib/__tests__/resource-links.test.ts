import { describe, expect, it } from "vitest";
import { chatLinkFor, isAgentUri, managerChatPath, managerRouteFor, publicChatLinkFor } from "../resource-links";

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

  it("sends people to the Manager's chat, which can sign them in", () => {
    // The standalone Chat UI has no sign-in, so a link there to anything but a
    // published agent hung behind a 401.
    expect(managerChatPath(ID, "Support")).toBe(`/manage/chat?agentId=${ID}&agentName=Support`);
    expect(managerChatPath(ID)).toBe(`/manage/chat?agentId=${ID}`);
    expect(chatLinkFor(ID, null, "https://eddi.example.com")).toBe(`https://eddi.example.com/manage/chat?agentId=${ID}`);
  });

  it("keeps the public Chat UI address for published agents", () => {
    expect(publicChatLinkFor(ID, "https://eddi.example.com")).toBe(`https://eddi.example.com/chat/production/${ID}`);
  });
});
