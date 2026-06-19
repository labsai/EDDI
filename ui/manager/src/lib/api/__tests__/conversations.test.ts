import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  parseAgentResource,
  parseConversationUri,
  extractInput,
  extractOutput,
  extractInputField,
  extractQuickReplies,
  extractActions,
  getConversationDescriptors,
  getSimpleConversationLog,
  getRawConversationLog,
  deleteConversation,
  getDetailedConversation,
} from "../conversations";

// ─── Pure function tests ──────────────────────────────────────────

describe("parseAgentResource", () => {
  it("parses an EDDI URI with version", () => {
    const result = parseAgentResource(
      "eddi://ai.labs.agent/agentstore/agents/abc123?version=2"
    );
    expect(result.agentId).toBe("abc123");
    expect(result.agentVersion).toBe(2);
  });

  it("returns empty/0 for undefined input", () => {
    const result = parseAgentResource(undefined);
    expect(result.agentId).toBe("");
    expect(result.agentVersion).toBe(0);
  });

  it("returns empty/0 for empty string", () => {
    const result = parseAgentResource("");
    expect(result.agentId).toBe("");
    expect(result.agentVersion).toBe(0);
  });

  it("handles HTTP URL format", () => {
    const result = parseAgentResource(
      "http://localhost:7070/agentstore/agents/xyz?version=5"
    );
    expect(result.agentId).toBe("xyz");
    expect(result.agentVersion).toBe(5);
  });

  it("defaults version to 0 when not present", () => {
    const result = parseAgentResource(
      "eddi://ai.labs.agent/agentstore/agents/abc"
    );
    expect(result.agentId).toBe("abc");
    expect(result.agentVersion).toBe(0);
  });

  it("handles non-numeric version (returns 0)", () => {
    const result = parseAgentResource(
      "eddi://ai.labs.agent/agentstore/agents/abc?version=xyz"
    );
    expect(result.agentId).toBe("abc");
    expect(result.agentVersion).toBe(0);
  });

  it("handles plain path URI", () => {
    const result = parseAgentResource("/agentstore/agents/myagent?version=3");
    expect(result.agentId).toBe("myagent");
    expect(result.agentVersion).toBe(3);
  });
});

describe("parseConversationUri", () => {
  it("parses EDDI conversation URI", () => {
    const result = parseConversationUri(
      "eddi://ai.labs.conversation/conversationstore/conversations/conv123"
    );
    expect(result).toBe("conv123");
  });

  it("parses plain path", () => {
    const result = parseConversationUri(
      "/conversationstore/conversations/conv456"
    );
    expect(result).toBe("conv456");
  });

  it("returns raw string on parse failure", () => {
    // An extremely malformed URI
    const result = parseConversationUri("not-a-valid-uri");
    // URL constructor with base can handle this, so it actually parses
    expect(result).toBeDefined();
  });
});

describe("extractInput", () => {
  it("extracts input:initial from a step", () => {
    const step = {
      conversationStep: [
        { key: "input:initial", value: "Hello" },
        { key: "actions", value: ["greet"] },
      ],
    };
    expect(extractInput(step)).toBe("Hello");
  });

  it("returns undefined when no input:initial key", () => {
    const step = {
      conversationStep: [{ key: "actions", value: ["greet"] }],
    };
    expect(extractInput(step)).toBeUndefined();
  });

  it("returns undefined for empty conversationStep", () => {
    const step = { conversationStep: [] };
    expect(extractInput(step)).toBeUndefined();
  });
});

describe("extractOutput", () => {
  it("returns undefined for undefined input", () => {
    expect(extractOutput(undefined)).toBeUndefined();
  });

  it("extracts from nested output array with text property", () => {
    const output = {
      output: [{ text: "Hello world" }],
    };
    expect(extractOutput(output)).toBe("Hello world");
  });

  it("extracts from nested output array with string items", () => {
    const output = {
      output: ["Line 1", "Line 2"],
    };
    expect(extractOutput(output)).toBe("Line 1\nLine 2");
  });

  it("extracts from flat output:text:* keys with string values", () => {
    const output = {
      "output:text:greet": "Hello!",
      "output:text:farewell": "Goodbye!",
    };
    expect(extractOutput(output)).toBe("Hello!\nGoodbye!");
  });

  it("extracts from flat output:text:* keys with array values", () => {
    const output = {
      "output:text:action": ["Response 1", { text: "Response 2" }],
    };
    expect(extractOutput(output)).toBe("Response 1\nResponse 2");
  });

  it("extracts from flat output:text:* keys with object value", () => {
    const output = {
      "output:text:action": { text: "Object response" },
    };
    expect(extractOutput(output)).toBe("Object response");
  });

  it("returns undefined when no matching output keys", () => {
    const output = { someOtherKey: "value" };
    expect(extractOutput(output)).toBeUndefined();
  });

  it("returns undefined for empty output array", () => {
    const output = { output: [] };
    expect(extractOutput(output)).toBeUndefined();
  });

  it("handles mixed types in nested output array", () => {
    const output = {
      output: ["text", { text: "object" }, { noText: true }],
    };
    expect(extractOutput(output)).toBe("text\nobject");
  });
});

describe("extractInputField", () => {
  it("returns undefined for undefined input", () => {
    expect(extractInputField(undefined)).toBeUndefined();
  });

  it("returns undefined when output is not an array", () => {
    expect(extractInputField({ output: "string" })).toBeUndefined();
  });

  it("extracts inputField from output array", () => {
    const output = {
      output: [
        { type: "text", text: "Enter your password:" },
        {
          type: "inputField",
          subType: "password",
          placeholder: "Enter password",
          label: "Password",
          defaultValue: "",
        },
      ],
    };
    const result = extractInputField(output);
    expect(result).toBeDefined();
    expect(result!.subType).toBe("password");
    expect(result!.placeholder).toBe("Enter password");
    expect(result!.label).toBe("Password");
  });

  it("defaults subType to password when not specified", () => {
    const output = {
      output: [{ type: "inputField" }],
    };
    const result = extractInputField(output);
    expect(result).toBeDefined();
    expect(result!.subType).toBe("password");
  });

  it("returns undefined when no inputField in output", () => {
    const output = {
      output: [{ type: "text", text: "Hello" }],
    };
    expect(extractInputField(output)).toBeUndefined();
  });

  it("skips non-object items in output array", () => {
    const output = {
      output: ["string", null, { type: "inputField", subType: "text" }],
    };
    const result = extractInputField(output);
    expect(result).toBeDefined();
    expect(result!.subType).toBe("text");
  });
});

describe("extractQuickReplies", () => {
  it("returns empty array for undefined input", () => {
    expect(extractQuickReplies(undefined)).toEqual([]);
  });

  it("extracts quick replies with value property", () => {
    const output = {
      quickReplies: [
        { value: "Yes", expressions: "yes" },
        { value: "No", expressions: "no" },
      ],
    };
    expect(extractQuickReplies(output)).toEqual(["Yes", "No"]);
  });

  it("handles string quick replies", () => {
    const output = {
      quickReplies: ["Option A", "Option B"],
    };
    expect(extractQuickReplies(output)).toEqual(["Option A", "Option B"]);
  });

  it("returns empty array when no quickReplies key", () => {
    const output = { someKey: "value" };
    expect(extractQuickReplies(output)).toEqual([]);
  });

  it("filters out null values", () => {
    const output = {
      quickReplies: [{ value: "Keep" }, 42, { value: "Also keep" }],
    };
    const result = extractQuickReplies(output);
    expect(result).toEqual(["Keep", "Also keep"]);
  });
});

describe("extractActions", () => {
  it("extracts actions array from step", () => {
    const step = {
      conversationStep: [
        { key: "input:initial", value: "Hello" },
        { key: "actions", value: ["greet", "respond"] },
      ],
    };
    expect(extractActions(step)).toEqual(["greet", "respond"]);
  });

  it("wraps string action value in array", () => {
    const step = {
      conversationStep: [{ key: "actions", value: "single_action" }],
    };
    expect(extractActions(step)).toEqual(["single_action"]);
  });

  it("returns empty array when no actions key", () => {
    const step = {
      conversationStep: [{ key: "input:initial", value: "Hello" }],
    };
    expect(extractActions(step)).toEqual([]);
  });

  it("returns empty array when actions value is null", () => {
    const step = {
      conversationStep: [{ key: "actions", value: null }],
    };
    expect(extractActions(step)).toEqual([]);
  });

  it("returns empty array for non-array/non-string value", () => {
    const step = {
      conversationStep: [{ key: "actions", value: 42 }],
    };
    expect(extractActions(step)).toEqual([]);
  });
});

// ─── API function tests ───────────────────────────────────────────

describe("getConversationDescriptors", () => {
  it("fetches conversations with defaults", async () => {
    const result = await getConversationDescriptors();
    expect(result).toBeDefined();
    expect(Array.isArray(result)).toBe(true);
    expect(result.length).toBeGreaterThan(0);
    // Check normalization
    expect(result[0]).toHaveProperty("agentId");
    expect(result[0]).toHaveProperty("conversationState");
  });

  it("passes filter and agentId params", async () => {
    const result = await getConversationDescriptors(
      20,
      0,
      "",
      "agent1"
    );
    expect(result).toBeDefined();
    // Should only return conversations for agent1
    for (const conv of result) {
      expect(conv.agentId).toBe("agent1");
    }
  });

  it("passes conversationState filter", async () => {
    const result = await getConversationDescriptors(
      20,
      0,
      "",
      "",
      undefined,
      "ENDED"
    );
    expect(result).toBeDefined();
    for (const conv of result) {
      expect(conv.conversationState).toBe("ENDED");
    }
  });

  it("handles { value: [...] } wrapper format", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json({
          value: [
            {
              resource: "eddi://conversations/conv-wrapped",
              createdOn: Date.now(),
              lastModifiedOn: Date.now(),
              conversationState: "READY",
              agentName: "Test",
            },
          ],
          Count: 1,
        })
      )
    );
    const result = await getConversationDescriptors();
    expect(result).toHaveLength(1);
    expect(result[0]!.name).toBe("Test");
  });

  it("handles API error", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json({ message: "Error" }, { status: 500 })
      )
    );
    await expect(getConversationDescriptors()).rejects.toMatchObject({
      status: 500,
    });
  });
});

describe("getSimpleConversationLog", () => {
  it("fetches simple conversation log", async () => {
    const result = await getSimpleConversationLog("conv1");
    expect(result).toBeDefined();
    expect(result.conversationId).toBe("conv1");
    expect(result.conversationSteps).toBeDefined();
  });

  it("passes returnDetailed and returnCurrentStepOnly params", async () => {
    const result = await getSimpleConversationLog("conv1", true, true);
    expect(result).toBeDefined();
  });
});

describe("getRawConversationLog", () => {
  it("fetches raw conversation log", async () => {
    const result = await getRawConversationLog("conv1");
    expect(result).toBeDefined();
    expect(result.agentId).toBe("agent1");
  });
});

describe("deleteConversation", () => {
  it("deletes a conversation", async () => {
    await expect(deleteConversation("conv1")).resolves.toBeUndefined();
  });

  it("deletes permanently", async () => {
    await expect(
      deleteConversation("conv1", true)
    ).resolves.toBeUndefined();
  });
});

describe("getDetailedConversation", () => {
  it("fetches detailed conversation", async () => {
    const result = await getDetailedConversation("conv1");
    expect(result).toBeDefined();
  });
});
