import { http, HttpResponse } from "msw";

const AGENTS_MOCK = [
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=3",
    name: "Support Agent",
    description: "24/7 customer support with order tracking, returns processing, and live-agent escalation",
    createdOn: Date.now() - 12 * 86400000,
    lastModifiedOn: Date.now() - 3600000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent2?version=2",
    name: "FAQ Agent",
    description: "Self-service knowledge base answering product, billing, and account questions",
    createdOn: Date.now() - 14 * 86400000,
    lastModifiedOn: Date.now() - 7200000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent3?version=1",
    name: "Appointment Scheduler",
    description: "Books, reschedules, and cancels appointments with calendar integration and reminders",
    createdOn: Date.now() - 10 * 86400000,
    lastModifiedOn: Date.now() - 2 * 86400000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent4?version=2",
    name: "Invoice Analyst",
    description: "Extracts line items from uploaded invoices, validates totals, and flags discrepancies",
    createdOn: Date.now() - 8 * 86400000,
    lastModifiedOn: Date.now() - 86400000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent5?version=1",
    name: "Product Recommender",
    description: "Suggests products based on browsing history, preferences, and real-time inventory",
    createdOn: Date.now() - 6 * 86400000,
    lastModifiedOn: Date.now() - 4 * 3600000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent6?version=1",
    name: "Employee Onboarding Guide",
    description: "Walks new hires through IT setup, policy acknowledgement, and benefits enrollment",
    createdOn: Date.now() - 5 * 86400000,
    lastModifiedOn: Date.now() - 2 * 3600000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent7?version=1",
    name: "Contract Review Assistant",
    description: "Analyzes legal contracts using RAG, highlights key clauses, and flags risk areas",
    createdOn: Date.now() - 3 * 86400000,
    lastModifiedOn: Date.now() - 5 * 3600000,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/agent8?version=1",
    name: "IT Helpdesk Bot",
    description: "Troubleshoots common IT issues, resets passwords, and creates Jira tickets for escalation",
    createdOn: Date.now() - 2 * 86400000,
    lastModifiedOn: Date.now() - 1800000,
  },
];

const WORKFLOWS_MOCK = [
  {
    resource: "eddi://ai.labs.workflow/workflowstore/workflows/pkg1?version=2",
    name: "Support Ticket Pipeline",
    description: "Parser → intent rules → LLM response → output with escalation fallback",
    createdOn: Date.now() - 12 * 86400000,
    lastModifiedOn: Date.now() - 3600000,
  },
  {
    resource: "eddi://ai.labs.workflow/workflowstore/workflows/pkg2?version=1",
    name: "FAQ Lookup Flow",
    description: "Dictionary matching → behavior rules → LLM answer → quick-reply output",
    createdOn: Date.now() - 14 * 86400000,
    lastModifiedOn: Date.now() - 7200000,
  },
  {
    resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf3?version=1",
    name: "Appointment Booking Pipeline",
    description: "Slot extraction → calendar API → confirmation output with reminder scheduling",
    createdOn: Date.now() - 10 * 86400000,
    lastModifiedOn: Date.now() - 2 * 86400000,
  },
  {
    resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf4?version=2",
    name: "Invoice Processing Flow",
    description: "Document parser → field extraction → validation rules → CRM API update",
    createdOn: Date.now() - 8 * 86400000,
    lastModifiedOn: Date.now() - 86400000,
  },
  {
    resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf5?version=1",
    name: "Product Recommendation Engine",
    description: "User context → RAG product catalog → LLM ranking → personalized output",
    createdOn: Date.now() - 6 * 86400000,
    lastModifiedOn: Date.now() - 4 * 3600000,
  },
  {
    resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf6?version=1",
    name: "Contract Analysis Pipeline",
    description: "RAG legal corpus → clause extraction → risk scoring → structured report output",
    createdOn: Date.now() - 3 * 86400000,
    lastModifiedOn: Date.now() - 5 * 3600000,
  },
];

const CONVERSATIONS_MOCK = [
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv1",
    name: "", description: "",
    createdOn: Date.now() - 1800000,
    lastModifiedOn: Date.now() - 300000,
    agentId: "agent1", agentVersion: 3,
    conversationState: "READY",
    viewState: "UNSEEN",
    conversationStepSize: 8,
    environment: "production",
    agentName: "Support Agent",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv2",
    name: "", description: "",
    createdOn: Date.now() - 86400000,
    lastModifiedOn: Date.now() - 3600000,
    agentId: "agent2", agentVersion: 2,
    conversationState: "ENDED",
    viewState: "SEEN",
    conversationStepSize: 12,
    environment: "production",
    agentName: "FAQ Agent",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv3",
    name: "", description: "",
    createdOn: Date.now() - 7200000,
    lastModifiedOn: Date.now() - 600000,
    agentId: "agent1", agentVersion: 3,
    conversationState: "IN_PROGRESS",
    viewState: "UNSEEN",
    conversationStepSize: 3,
    environment: "production",
    agentName: "Support Agent",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv4",
    name: "", description: "",
    createdOn: Date.now() - 2 * 86400000,
    lastModifiedOn: Date.now() - 2 * 3600000,
    agentId: "agent3", agentVersion: 1,
    conversationState: "ENDED",
    viewState: "SEEN",
    conversationStepSize: 6,
    environment: "production",
    agentName: "Appointment Scheduler",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv5",
    name: "", description: "",
    createdOn: Date.now() - 3 * 86400000,
    lastModifiedOn: Date.now() - 86400000,
    agentId: "agent4", agentVersion: 2,
    conversationState: "ENDED",
    viewState: "SEEN",
    conversationStepSize: 4,
    environment: "production",
    agentName: "Invoice Analyst",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv6",
    name: "", description: "",
    createdOn: Date.now() - 3600000,
    lastModifiedOn: Date.now() - 900000,
    agentId: "agent5", agentVersion: 1,
    conversationState: "READY",
    viewState: "UNSEEN",
    conversationStepSize: 5,
    environment: "production",
    agentName: "Product Recommender",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv7",
    name: "", description: "",
    createdOn: Date.now() - 4 * 86400000,
    lastModifiedOn: Date.now() - 3 * 86400000,
    agentId: "agent6", agentVersion: 1,
    conversationState: "ENDED",
    viewState: "SEEN",
    conversationStepSize: 14,
    environment: "production",
    agentName: "Employee Onboarding Guide",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv8",
    name: "", description: "",
    createdOn: Date.now() - 5400000,
    lastModifiedOn: Date.now() - 120000,
    agentId: "agent7", agentVersion: 1,
    conversationState: "IN_PROGRESS",
    viewState: "UNSEEN",
    conversationStepSize: 2,
    environment: "production",
    agentName: "Contract Review Assistant",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv9",
    name: "", description: "",
    createdOn: Date.now() - 7 * 86400000,
    lastModifiedOn: Date.now() - 6 * 86400000,
    agentId: "agent8", agentVersion: 1,
    conversationState: "ENDED",
    viewState: "SEEN",
    conversationStepSize: 9,
    environment: "production",
    agentName: "IT Helpdesk Bot",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv10",
    name: "", description: "",
    createdOn: Date.now() - 10800000,
    lastModifiedOn: Date.now() - 1200000,
    agentId: "agent1", agentVersion: 3,
    conversationState: "ERROR",
    viewState: "UNSEEN",
    conversationStepSize: 1,
    environment: "test",
    agentName: "Support Agent",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv11",
    name: "", description: "",
    createdOn: Date.now() - 2 * 3600000,
    lastModifiedOn: Date.now() - 3600000,
    agentId: "agent5", agentVersion: 1,
    conversationState: "READY",
    viewState: "SEEN",
    conversationStepSize: 7,
    environment: "test",
    agentName: "Product Recommender",
  },
  {
    resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv12",
    name: "", description: "",
    createdOn: Date.now() - 5 * 86400000,
    lastModifiedOn: Date.now() - 4 * 86400000,
    agentId: "agent4", agentVersion: 2,
    conversationState: "ENDED",
    viewState: "SEEN",
    conversationStepSize: 10,
    environment: "production",
    agentName: "Invoice Analyst",
  },
];

// --- Enriched JSON Schemas (matching victools Draft 2020-12 output) ---
const RESOURCE_SCHEMAS: Record<string, object> = {
  behavior: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "BehaviorConfiguration",
    properties: {
      appendActions: { type: "boolean", description: "Whether to append actions from behavior rules to existing actions" },
      expressionsAsActions: { type: "boolean", description: "Whether to use expressions as actions" },
      behaviorGroups: {
        type: "array",
        description: "Groups of behavior rules",
        items: {
          type: "object",
          properties: {
            name: { type: "string", description: "Name of the behavior group" },
            executionStrategy: { type: "string", description: "Execution strategy: currentStepOnly or allSteps" },
            behaviorRules: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  name: { type: "string", description: "Rule name" },
                  actions: { type: "array", items: { type: "string" }, description: "Actions to trigger" },
                  conditions: { type: "array", items: { type: "object" }, description: "Conditions to evaluate" },
                },
              },
            },
          },
        },
      },
    },
  },
  httpcalls: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "HttpCallsConfiguration",
    properties: {
      targetServerUrl: { type: "string", description: "Base URL of the target server" },
      httpCalls: {
        type: "array",
        description: "List of HTTP call definitions",
        items: {
          type: "object",
          properties: {
            name: { type: "string", description: "Name of the HTTP call" },
            description: { type: "string", description: "Description of what this call does" },
            actions: { type: "array", items: { type: "string" }, description: "Actions that trigger this call" },
            saveResponse: { type: "boolean", description: "Whether to save the response" },
            responseObjectName: { type: "string", description: "Key to store response under" },
            fireAndForget: { type: "boolean", description: "Whether to wait for response" },
            request: {
              type: "object",
              properties: {
                path: { type: "string", description: "URL path (supports Thymeleaf templates)" },
                method: { type: "string", description: "HTTP method (GET, POST, PUT, DELETE, PATCH)" },
                headers: { type: "object", additionalProperties: { type: "string" } },
                contentType: { type: "string" },
                body: { type: "string" },
                queryParams: { type: "object", additionalProperties: { type: "string" } },
              },
            },
            preRequest: { type: "object", properties: { propertyInstructions: { type: "array" } } },
            postResponse: {
              type: "object",
              properties: {
                propertyInstructions: { type: "array" },
                outputBuildInstructions: { type: "array" },
                qrBuildInstructions: { type: "array" },
              },
            },
          },
        },
      },
    },
  },
  output: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "OutputConfigurationSet",
    properties: {
      lang: { type: "string", description: "Language code (e.g. en, de, fr)" },
      outputSet: {
        type: "array",
        description: "List of output configurations",
        items: {
          type: "object",
          properties: {
            action: { type: "string", description: "Action that triggers this output" },
            timesOccurred: { type: "integer", description: "Number of occurrences to match (0 = any)" },
            outputs: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  valueAlternatives: {
                    type: "array",
                    items: {
                      type: "object",
                      properties: {
                        type: { type: "string", description: "Output type: text, image, quickReply, button, etc." },
                        text: { type: "string" },
                        delay: { type: "integer" },
                      },
                    },
                  },
                },
              },
            },
            quickReplies: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  value: { type: "string", description: "Display text" },
                  expressions: { type: "string", description: "Expression to evaluate" },
                  isDefault: { type: "boolean" },
                },
              },
            },
          },
        },
      },
    },
  },
  dictionary: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "RegularDictionaryConfiguration",
    properties: {
      lang: { type: "string", description: "Language code" },
      words: {
        type: "array",
        description: "Word definitions",
        items: {
          type: "object",
          description: "A word definition of the dictionary.",
          properties: {
            word: { type: "string", description: "A word of a natural language that you want the parser to recognize (e.g. hello)." },
            expressions: { type: "string", description: "Prolog like expressions describing the meaning of this word (e.g. greeting(hello))" },
            frequency: { type: "integer", description: "Word frequency weight" },
          },
        },
      },
      phrases: {
        type: "array",
        description: "Phrase definitions",
        items: {
          type: "object",
          description: "A phrase definition of the dictionary.",
          properties: {
            phrase: { type: "string", description: "A phrase to recognize (e.g. good morning)." },
            expressions: { type: "string", description: "Prolog like expressions describing the meaning" },
          },
        },
      },
      regExs: {
        type: "array",
        description: "Regular expression definitions",
        items: {
          type: "object",
          description: "A RegEx definition of the dictionary.",
          properties: {
            regEx: { type: "string", description: "A regular expression pattern" },
            expressions: { type: "string", description: "Prolog like expressions describing the meaning" },
          },
        },
      },
    },
  },
  langchain: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "LangChainConfiguration",
    properties: {
      tasks: {
        type: "array",
        description: "List of LangChain tasks",
        items: {
          type: "object",
          properties: {
            id: { type: "string", description: "Unique task identifier" },
            type: { type: "string", description: "LLM provider: openai, anthropic, gemini, ollama, mistral, huggingface" },
            description: { type: "string", description: "Task description" },
            actions: { type: "array", items: { type: "string" }, description: "Actions that trigger this task" },
            parameters: {
              type: "object",
              description: "Provider-specific parameters",
              properties: {
                systemMessage: { type: "string", description: "System prompt for the LLM" },
                addToOutput: { type: "string", description: "Whether to add response to output" },
                logSizeLimit: { type: "string", description: "Maximum conversation log entries" },
              },
              additionalProperties: { type: "string" },
            },
            tools: { type: "array", items: { type: "string" }, description: "URIs to HTTP calls configs used as tools" },
            enableBuiltInTools: { type: "boolean", description: "Whether to enable built-in tools" },
            enableHttpCallTools: { type: "boolean", description: "Auto-discover httpcall extensions from the workflow as tools (default: true)" },
            builtInToolsWhitelist: { type: "array", items: { type: "string" }, description: "Whitelist of built-in tool names" },
            conversationHistoryLimit: { type: "integer", description: "Max conversation history entries sent to LLM" },
            maxBudgetPerConversation: { type: "number", description: "Maximum cost budget per conversation" },
            enableCostTracking: { type: "boolean" },
            enableToolCaching: { type: "boolean" },
            enableRateLimiting: { type: "boolean" },
            defaultRateLimit: { type: "integer", description: "Default rate limit per minute" },
          },
        },
      },
    },
  },
  propertysetter: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "PropertySetterConfiguration",
    properties: {
      setOnActions: {
        type: "array",
        description: "List of property setter definitions triggered by actions",
        items: {
          type: "object",
          properties: {
            actions: { type: "array", items: { type: "string" }, description: "Actions that trigger this setter" },
            setProperties: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  name: { type: "string", description: "Property name" },
                  valueString: { type: "string", description: "Value to set (supports Thymeleaf expressions)" },
                  scope: { type: "string", description: "Scope: conversation, longTerm, or step" },
                  fromObjectPath: { type: "string", description: "Path to read value from" },
                  override: { type: "boolean", description: "Whether to override existing value" },
                },
              },
            },
          },
        },
      },
    },
  },
  rag: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "RagConfiguration",
    description: "Knowledge Base configuration for RAG (Retrieval-Augmented Generation)",
    properties: {
      name: { type: "string", description: "Display name for this knowledge base" },
      embeddingProvider: { type: "string", description: "Embedding model provider: openai, ollama, vertex, google, huggingface, jlama" },
      embeddingParameters: { type: "object", additionalProperties: { type: "string" }, description: "Provider-specific parameters (model, apiKey, baseUrl)" },
      storeType: { type: "string", description: "Vector store type: in-memory, pgvector, mongodb-atlas, qdrant" },
      storeParameters: { type: "object", additionalProperties: { type: "string" }, description: "Store-specific connection parameters" },
      isolationStrategy: { type: "string", description: "Tenant isolation: collection or metadata" },
      chunkStrategy: { type: "string", description: "Chunking strategy: recursive, paragraph, sentence" },
      chunkSize: { type: "integer", description: "Chunk size in characters (default: 512)" },
      chunkOverlap: { type: "integer", description: "Chunk overlap in characters (default: 64)" },
      maxResults: { type: "integer", description: "Default max results to return (top-K)" },
      minScore: { type: "number", description: "Default minimum similarity score (0.0-1.0)" },
    },
  },
};

// ─── Audit mock data generator ─────────────────────────────────────
const TASK_TYPES = ["langchain", "behavior", "output", "httpcalls", "propertysetter", "expressions"];
function generateMockAuditEntries(conversationId: string, count: number) {
  const now = Date.now();
  return Array.from({ length: count }, (_, i) => ({
    id: `audit-${conversationId}-${i}`,
    conversationId,
    agentId: "agent1",
    agentVersion: 1,
    userId: "manager-user",
    environment: i % 3 === 0 ? "production" : "test",
    stepIndex: Math.floor(i / 2),
    taskId: `task-${i}`,
    taskType: TASK_TYPES[i % TASK_TYPES.length]!,
    taskIndex: i % 2,
    durationMs: 50 + Math.floor(Math.random() * 500),
    input: i === 0 ? { "input:initial": "Tell me about the weather" } : null,
    output: i % 2 === 0 ? { "output:text": "Here's the latest weather information..." } : null,
    llmDetail: TASK_TYPES[i % TASK_TYPES.length] === "langchain" ? {
      model: "gpt-5.4-mini",
      modelName: "gpt-5.4-mini",
      provider: "openai",
      tokens: { input: 128, output: 64 },
      tokenUsage: { inputTokens: 128, outputTokens: 64 },
      compiledPrompt: JSON.stringify([
        { role: "system", content: "You are a helpful weather assistant." },
        { role: "user", content: "Tell me about the weather in Vienna" },
      ]),
      modelResponse: "The weather in Vienna is currently sunny with a temperature of 22°C and humidity of 45%.",
    } : null,
    toolCalls: i === 3 ? [{ name: "fetch_weather", args: { city: "Vienna" }, result: "sunny 22°C" }] : null,
    actions: ["greet", "respond"].slice(0, (i % 2) + 1),
    cost: TASK_TYPES[i % TASK_TYPES.length] === "langchain" ? 0.003 + Math.random() * 0.01 : 0,
    timestamp: new Date(now - (count - i) * 60000).toISOString(),
    hmac: i % 4 === 0 ? "a1b2c3d4e5f6" : null,
  }));
}

export const handlers = [
  // Template preview — resolves Qute templates for the LLM editor preview
  http.post("*/administration/preview/template", async ({ request }) => {
    const body = (await request.json()) as { template?: string; conversationId?: string };
    const template = body.template ?? "";
    // Sample data matching what the real backend provides
    const sampleData: Record<string, unknown> = {
      "properties.userName": "Alice",
      "properties.language": "en",
      "properties.email": "alice@example.com",
      "memory.current.input": "What is my order status?",
      "memory.current.actions": "check_order, respond",
      "memory.last.input": "Hello",
      "memory.last.output": "Welcome! How can I help you today?",
      "context.output": "Previous context value",
      "snippets.tone": "Be professional and concise.",
      "snippets.safety": "Do not reveal internal system details.",
      "userInfo.userId": "user-12345",
      "conversationInfo.conversationId": body.conversationId ?? "conv-67890",
      "conversationInfo.agentId": "agent-abc",
      "conversationInfo.agentVersion": "1",
      "input": "What is my order status?",
    };
    // Simple template resolution: replace {key} with values
    let resolved = template;
    for (const [key, val] of Object.entries(sampleData)) {
      resolved = resolved.split(`{${key}}`).join(String(val));
    }
    // Strip {#if ...} ... {/if} and {#for ...} ... {/for} blocks (just show inner content)
    resolved = resolved.replace(/\{#if[^}]*\}\n?/g, "");
    resolved = resolved.replace(/\{\/if\}\n?/g, "");
    resolved = resolved.replace(/\{#for[^}]*\}\n?/g, "");
    resolved = resolved.replace(/\{\/for\}\n?/g, "");
    resolved = resolved.replace(/\{#else\}\n?/g, "");
    return HttpResponse.json({
      resolved,
      availableVariables: Object.keys(sampleData),
      variableValues: sampleData,
      error: null,
    });
  }),

  // Descriptor PATCH — used by create-workflow/create-agent to set name/description
  http.patch("*/descriptorstore/descriptors/:id", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // JSON Schema endpoints for agents and packages
  http.get("*/agentstore/agents/jsonSchema", () => {
    return HttpResponse.json({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      title: "AgentConfiguration",
      properties: {
        workflows: {
          type: "array",
          description: "List of workflow URIs that make up this agent",
          items: { type: "string", format: "uri" },
        },
        channels: {
          type: "array",
          description: "Channel connectors for this agent",
          items: {
            type: "object",
            properties: {
              type: { type: "string", description: "Channel type identifier" },
              config: { type: "object", additionalProperties: true, description: "Channel-specific configuration" },
            },
          },
        },
      },
    });
  }),
  http.get("*/workflowstore/workflows/jsonSchema", () => {
    return HttpResponse.json({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      title: "WorkflowConfiguration",
      properties: {
        workflowSteps: {
          type: "array",
          description: "List of steps in this workflow",
          items: {
            type: "object",
            properties: {
              type: { type: "string", description: "Extension type (e.g. ai.labs.rules, ai.labs.llm)" },
              extensions: { type: "object", additionalProperties: true, description: "Extension-specific configuration" },
              config: {
                type: "object",
                properties: {
                  uri: { type: "string", format: "uri", description: "Resource URI for this extension" },
                },
              },
            },
          },
        },
      },
    });
  }),

  // Agent descriptors
  http.get("*/agentstore/agents/descriptors", () => {
    return HttpResponse.json(AGENTS_MOCK);
  }),

  // Get agent
  http.get("*/agentstore/agents/:id", ({ request, params }) => {
    const url = new URL(request.url);
    const version = parseInt(url.searchParams.get("version") ?? "1", 10);
    const agentId = params.id as string;
    // Per-agent configs for a realistic detail view
    const agentConfigs: Record<string, object> = {
      agent1: {
        workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/pkg1?version=2"],
        channels: [{ type: "web", config: { allowedOrigins: ["*"], maxIdleMinutes: 30 } }],
        a2aEnabled: true,
        description: "24/7 customer support with order tracking, returns processing, and escalation handling",
        a2aSkills: ["order-tracking", "return-processing", "escalation"],
        memory: { memoryType: "longTerm", maxConversationSteps: 100 },
      },
      agent3: {
        workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf3?version=1"],
        channels: [{ type: "web", config: { allowedOrigins: ["https://clinic.example.com"] } }],
        a2aEnabled: false,
        description: "Patient appointment scheduling with slot extraction and calendar integration",
        a2aSkills: [],
        memory: { memoryType: "shortTerm", maxConversationSteps: 20 },
      },
      agent4: {
        workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf4?version=2"],
        channels: [],
        a2aEnabled: true,
        description: "Automated invoice analysis with field extraction, validation, and CRM updates",
        a2aSkills: ["invoice-parsing", "data-validation"],
        memory: { memoryType: "longTerm", maxConversationSteps: 50 },
      },
    };
    const config = agentConfigs[agentId] ?? {
      workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/pkg1?version=2"],
      channels: [],
      a2aEnabled: false,
      description: "AI agent configured for EDDI platform",
      a2aSkills: [],
    };
    return HttpResponse.json({ ...config, _version: version });
  }),

  // Deployment status
  http.get("*/administration/:env/deploymentstatus/:agentId", () => {
    return HttpResponse.json({ status: "READY" });
  }),

  // Deploy agent
  http.post("*/administration/:env/deploy/:agentId", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Undeploy agent
  http.post("*/administration/:env/undeploy/:agentId", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Agent setup (wizard quick-create)
  http.post("*/administration/agents/setup", () => {
    const newId = `agent-setup-${Date.now()}`;
    return HttpResponse.json({
      action: "created",
      agentId: newId,
      agentName: "Setup Agent",
      provider: "openai",
      model: "gpt-5.4",
      deployed: true,
      deploymentStatus: "READY",
    });
  }),

  // Update agent
  http.put("*/agentstore/agents/:id", ({ request, params }) => {
    const url = new URL(request.url);
    const currentVersion = parseInt(
      url.searchParams.get("version") ?? "1",
      10
    );
    const newVersion = currentVersion + 1;
    return new HttpResponse(null, {
      status: 200,
      headers: {
        Location: `eddi://ai.labs.agent/agentstore/agents/${params.id}?version=${newVersion}`,
      },
    });
  }),

  // Create agent (bare POST without :id)
  http.post("*/agentstore/agents", ({ request }) => {
    const url = new URL(request.url);
    // Check if this is a duplicate request (has :id param with version query)
    const pathParts = url.pathname.split("/").filter(Boolean);
    const lastPart = pathParts[pathParts.length - 1];
    if (lastPart !== "agents") {
      // This is a duplicate request (/agents/:id?version=X&deepCopy=Y)
      const deepCopy = url.searchParams.get("deepCopy");
      const newId = `dup-${Date.now()}`;
      return new HttpResponse(null, {
        status: 201,
        headers: {
          Location: `eddi://ai.labs.agent/agentstore/agents/${newId}?version=1${deepCopy ? "&deepCopy=true" : ""}`,
        },
      });
    }
    // Create new agent
    const newId = `agent-${Date.now()}`;
    return new HttpResponse(null, {
      status: 201,
      headers: {
        Location: `eddi://ai.labs.agent/agentstore/agents/${newId}?version=1`,
      },
    });
  }),

  // Delete agent
  http.delete("*/agentstore/agents/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Workflow descriptors
  http.get("*/workflowstore/workflows/descriptors", () => {
    return HttpResponse.json(WORKFLOWS_MOCK);
  }),

  // Get package
  http.get("*/workflowstore/workflows/:id", () => {
    return HttpResponse.json({
      workflowSteps: [
        {
          type: "ai.labs.parser",
          extensions: {},
          config: { uri: "eddi://ai.labs.parser/parserstore/parsers/parser1?version=1" },
        },
        {
          type: "ai.labs.rules",
          extensions: {},
          config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
        },
        {
          type: "ai.labs.property",
          extensions: {},
          config: { uri: "eddi://ai.labs.property/propertysetterstore/propertysetters/ps1?version=1" },
        },
        {
          type: "ai.labs.llm",
          extensions: {},
          config: { uri: "eddi://ai.labs.llm/llmstore/llms/llm1?version=1" },
        },
        {
          type: "ai.labs.output",
          extensions: {},
          config: { uri: "eddi://ai.labs.output/outputstore/outputsets/out1?version=1" },
        },
      ],
    });
  }),

  // Create workflow
  http.post("*/workflowstore/workflows", () => {
    const newId = `wf-${Date.now()}`;
    return new HttpResponse(null, {
      status: 201,
      headers: {
        Location: `eddi://ai.labs.workflow/workflowstore/workflows/${newId}?version=1`,
      },
    });
  }),

  // Delete package
  http.delete("*/workflowstore/workflows/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Conversation descriptors — supports agentId, conversationId, conversationState filters
  http.get("*/conversationstore/conversations", ({ request }) => {
    const url = new URL(request.url);
    const agentId = url.searchParams.get("agentId");
    const conversationId = url.searchParams.get("conversationId");
    const conversationState = url.searchParams.get("conversationState");
    let result = [...CONVERSATIONS_MOCK];
    if (agentId) result = result.filter((c) => c.agentId === agentId);
    if (conversationId) result = result.filter((c) => c.resource.includes(conversationId));
    if (conversationState) result = result.filter((c) => c.conversationState === conversationState);
    return HttpResponse.json(result);
  }),

  // Simple conversation log
  http.get("*/conversationstore/conversations/simple/:id", () => {
    const now = new Date();
    const stepTime = (offsetMs: number) => new Date(now.getTime() - offsetMs).toISOString();
    return HttpResponse.json({
      agentId: "agent1",
      agentVersion: 3,
      conversationId: "conv1",
      conversationState: "READY",
      environment: "production",
      undoAvailable: true,
      redoAvailable: false,
      conversationSteps: [
        {
          conversationStep: [
            { key: "input:initial", value: "Hi, I need help with my order", timestamp: stepTime(300000), originWorkflowId: null },
            { key: "actions", value: ["greet", "order_inquiry"], timestamp: stepTime(299500), originWorkflowId: "pkg1" },
            { key: "output:text:greet", value: "Hello! I'd be happy to help with your order. Could you share your order number?", timestamp: stepTime(299000), originWorkflowId: "pkg1" },
          ],
          timestamp: stepTime(300000),
        },
        {
          conversationStep: [
            { key: "input:initial", value: "It's ORD-2024-78542", timestamp: stepTime(240000), originWorkflowId: null },
            { key: "actions", value: ["lookup_order"], timestamp: stepTime(239500), originWorkflowId: "pkg1" },
            { key: "output:text:lookup_order", value: "I found your order ORD-2024-78542. It was placed on March 28th for a Wireless Keyboard ($89.99). It's currently in transit and expected to arrive by April 2nd. Is there anything specific you'd like to know about it?", timestamp: stepTime(238000), originWorkflowId: "pkg1" },
          ],
          timestamp: stepTime(240000),
        },
        {
          conversationStep: [
            { key: "input:initial", value: "Can I change the delivery address?", timestamp: stepTime(180000), originWorkflowId: null },
            { key: "actions", value: ["address_change"], timestamp: stepTime(179500), originWorkflowId: "pkg1" },
            { key: "output:text:address_change", value: "Since your order is already in transit, I can try to redirect the package. Please provide the new delivery address and I'll check if a redirect is possible with the carrier.", timestamp: stepTime(178000), originWorkflowId: "pkg1" },
            { key: "quickReplies", value: ["Keep current address", "Provide new address", "Cancel order instead"], timestamp: stepTime(177500), originWorkflowId: "pkg1" },
          ],
          timestamp: stepTime(180000),
        },
        {
          conversationStep: [
            { key: "input:initial", value: "123 Oak Street, Suite 4B, Portland OR 97201", timestamp: stepTime(120000), originWorkflowId: null },
            { key: "actions", value: ["update_address", "notify_carrier"], timestamp: stepTime(119000), originWorkflowId: "pkg1" },
            { key: "output:text:update_address", value: "Great news! I've submitted a redirect request to the carrier for: 123 Oak Street, Suite 4B, Portland OR 97201. You'll receive a confirmation email within the next 2 hours. The estimated delivery date may shift by 1 business day.", timestamp: stepTime(117000), originWorkflowId: "pkg1" },
          ],
          timestamp: stepTime(120000),
        },
        {
          conversationStep: [
            { key: "input:initial", value: "Perfect, thank you!", timestamp: stepTime(60000), originWorkflowId: null },
            { key: "actions", value: ["farewell"], timestamp: stepTime(59500), originWorkflowId: "pkg1" },
            { key: "output:text:farewell", value: "You're welcome! Your redirect reference is RDR-98765. Is there anything else I can help you with?", timestamp: stepTime(58000), originWorkflowId: "pkg1" },
            { key: "quickReplies", value: ["Track my order", "View other orders", "No thanks, goodbye"], timestamp: stepTime(57500), originWorkflowId: "pkg1" },
          ],
          timestamp: stepTime(60000),
        },
      ],
      conversationOutputs: [
        { "output:text:greet": "Hello! I'd be happy to help with your order. Could you share your order number?" },
        { "output:text:lookup_order": "I found your order ORD-2024-78542. It was placed on March 28th for a Wireless Keyboard ($89.99). It's currently in transit and expected to arrive by April 2nd." },
        { "output:text:address_change": "Since your order is already in transit, I can try to redirect the package." },
        { "output:text:update_address": "Great news! I've submitted a redirect request to the carrier." },
        { "output:text:farewell": "You're welcome! Your redirect reference is RDR-98765." },
      ],
      conversationProperties: {
        agentName: "Support Agent",
        userId: "user-42",
        channel: "web",
      },
    });
  }),

  // Raw conversation log
  http.get("*/conversationstore/conversations/:id", () => {
    return HttpResponse.json({
      agentId: "agent1",
      agentVersion: 1,
      conversationId: "conv1",
      conversationState: "READY",
      environment: "production",
      conversationSteps: [],
    });
  }),

  // Delete conversation
  http.delete("*/conversationstore/conversations/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // --- Chat / Agent Engine ---

  // Start conversation (v6: POST /agents/:agentId/start)
  http.post("*/agents/:agentId/start", () => {
    return new HttpResponse(null, {
      status: 201,
      headers: {
        Location: `/agents/conv-${Date.now()}`,
      },
    });
  }),

  // Send message (text/plain or JSON) — returns snapshot (v6: POST /agents/:conversationId)
  http.post("*/agents/:conversationId", () => {
    return HttpResponse.json({
      agentId: "agent1",
      agentVersion: 3,
      conversationId: "conv-mock",
      conversationState: "READY",
      environment: "production",
      conversationSteps: [
        {
          input: "",
          output: "I'd be happy to help! I can assist you with order tracking, returns, account inquiries, or product recommendations. What would you like help with?",
          actions: ["respond"],
          quickReplies: ["Track my order", "Start a return", "Browse products"],
        },
      ],
      conversationProperties: {
        agentName: "Support Agent",
        userId: "user-42",
        channel: "web",
      },
    });
  }),

  // Read conversation (v6: GET /agents/:conversationId)
  http.get("*/agents/:conversationId", () => {
    return HttpResponse.json({
      agentId: "agent1",
      agentVersion: 3,
      conversationId: "conv-mock",
      conversationState: "READY",
      environment: "production",
      conversationSteps: [
        {
          output: "Welcome to EDDI Support! I can help with orders, returns, billing, or product questions. How can I assist you today?",
          actions: ["welcome"],
          quickReplies: ["Order help", "Returns", "Billing question", "Something else"],
        },
      ],
      conversationProperties: {
        agentName: "Support Agent",
        userId: "user-42",
        channel: "web",
      },
    });
  }),

  // --- Backup / Import / Export ---
  http.post("*/backup/export/:agentId", () => {
    return new HttpResponse(null, {
      status: 200,
      headers: { Location: "/backup/export/test-agent-1.zip" },
    });
  }),

  http.get("*/backup/export/:filename", () => {
    return new HttpResponse(new Blob(["fake-zip"]), {
      status: 200,
      headers: { "Content-Type": "application/zip" },
    });
  }),

  http.post("*/backup/import/preview", () => {
    return HttpResponse.json({
      agentOriginId: "origin-agent-1",
      agentName: "Weather Agent",
      resources: [
        {
          originId: "origin-agent-1",
          resourceType: "agent",
          name: "Weather Agent",
          action: "UPDATE",
          localId: "agent1",
          localVersion: 1,
        },
        {
          originId: "origin-pkg-1",
          resourceType: "package",
          name: "Main Workflow",
          action: "UPDATE",
          localId: "pkg1",
          localVersion: 1,
        },
        {
          originId: "origin-beh-1",
          resourceType: "behavior",
          name: "Greeting Rules",
          action: "CREATE",
          localId: null,
          localVersion: null,
        },
        {
          originId: "origin-dict-1",
          resourceType: "dictionary",
          name: "English Dictionary",
          action: "UPDATE",
          localId: "dict1",
          localVersion: 1,
        },
      ],
    });
  }),

  // NOTE: This generic handler must come AFTER the /preview handler
  http.post("*/backup/import", ({ request }) => {
    const url = new URL(request.url);
    const strategy = url.searchParams.get("strategy");
    if (strategy === "merge") {
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: "/agentstore/agents/agent1?version=2" },
      });
    }
    return new HttpResponse(null, {
      status: 200,
      headers: { Location: "/agentstore/agents/imported-agent?version=1" },
    });
  }),

  // --- Extension Store ---
  http.get("*/extensionstore/extensions", () => {
    return HttpResponse.json([
      { type: "ai.labs.parser", displayName: "Parser", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.rules", displayName: "Rules", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.property", displayName: "Property Setter", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.apicalls", displayName: "API Calls", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.llm", displayName: "LLM", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.output", displayName: "Output", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.output.template", displayName: "Output Template", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.mcpcalls", displayName: "MCP Calls", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.rag", displayName: "RAG Knowledge Base", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
    ]);
  }),

  // Update package
  http.put("*/workflowstore/workflows/:id", ({ request, params }) => {
    const url = new URL(request.url);
    const currentVersion = parseInt(
      url.searchParams.get("version") ?? "1",
      10
    );
    const newVersion = currentVersion + 1;
    return new HttpResponse(null, {
      status: 200,
      headers: {
        Location: `eddi://ai.labs.workflow/workflowstore/workflows/${params.id}?version=${newVersion}`,
      },
    });
  }),

  // --- Resource Stores ---
  // Specific handlers for behavior and httpcalls with realistic mock data
  http.get("*/rulestore/rulesets/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    // Don't match descriptor endpoints
    if (url.pathname.endsWith("/descriptors") || includePrevious) {
      return;
    }
    return HttpResponse.json({
      appendActions: true,
      expressionsAsActions: false,
      behaviorGroups: [
        {
          name: "Greeting Rules",
          executionStrategy: "currentStepOnly",
          behaviorRules: [
            {
              name: "greeting_rule",
              actions: ["greet"],
              conditions: [
                {
                  type: "inputmatcher",
                  configs: {
                    expressions: "greeting(*)",
                    occurrence: "currentStep",
                  },
                },
              ],
            },
            {
              name: "fallback_rule",
              actions: ["fallback"],
              conditions: [
                {
                  type: "negation",
                  configs: {},
                  conditions: [
                    {
                      type: "actionmatcher",
                      configs: { actions: "greet" },
                    },
                  ],
                },
              ],
            },
          ],
        },
      ],
    });
  }),

  // OpenAPI endpoint discovery
  http.get("*/apicallstore/apicalls/discover-endpoints", ({ request }) => {
    const url = new URL(request.url);
    const specUrl = url.searchParams.get("specUrl");
    if (!specUrl) {
      return HttpResponse.json({ error: "specUrl query parameter is required" }, { status: 400 });
    }
    return HttpResponse.json({
      title: "Petstore API",
      baseUrl: "https://petstore.example.com/v1",
      endpointCount: 5,
      groups: {
        pets: {
          targetServerUrl: "https://petstore.example.com/v1",
          httpCalls: [
            {
              name: "listPets",
              description: "List all pets",
              actions: ["api_get_pets"],
              saveResponse: true,
              responseObjectName: "listPets_response",
              request: { path: "/pets", method: "get", headers: {}, queryParams: { limit: "{limit}" }, contentType: "application/json", body: "" },
              parameters: { limit: "Max items to return" },
            },
            {
              name: "createPet",
              description: "Create a pet",
              actions: ["api_post_pets"],
              saveResponse: true,
              responseObjectName: "createPet_response",
              request: { path: "/pets", method: "post", headers: {}, queryParams: {}, contentType: "application/json", body: '{\n  "name": "{name}",\n  "age": {age}\n}' },
              parameters: { name: "Pet name", age: "Pet age" },
            },
            {
              name: "getPet",
              description: "Get a pet by ID",
              actions: ["api_get_pets_petid"],
              saveResponse: true,
              responseObjectName: "getPet_response",
              request: { path: "/pets/{petId}", method: "get", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
              parameters: { petId: "The pet ID" },
            },
          ],
        },
        store: {
          targetServerUrl: "https://petstore.example.com/v1",
          httpCalls: [
            {
              name: "getInventory",
              description: "Returns pet inventories",
              actions: ["api_get_store_inventory"],
              saveResponse: true,
              responseObjectName: "getInventory_response",
              request: { path: "/store/inventory", method: "get", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
            },
            {
              name: "placeOrder",
              description: "Place an order",
              actions: ["api_post_store_order"],
              saveResponse: true,
              responseObjectName: "placeOrder_response",
              request: { path: "/store/order", method: "post", headers: {}, queryParams: {}, contentType: "application/json", body: '{\n  "petId": "{petId}",\n  "quantity": {quantity}\n}' },
              parameters: { petId: "Pet ID to order", quantity: "Number to order" },
            },
          ],
        },
      },
    });
  }),

  http.get("*/apicallstore/apicalls/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) {
      return;
    }
    return HttpResponse.json({
      targetServerUrl: "https://api.example.com",
      httpCalls: [
        {
          name: "get_weather",
          description: "Fetch current weather data for a city",
          parameters: { city: "City name to look up" },
          actions: ["get_weather"],
          saveResponse: true,
          responseObjectName: "weatherData",
          fireAndForget: false,
          request: {
            path: "/weather?city=[[${city}]]",
            method: "GET",
            headers: {
              Authorization: "Bearer [[${apiKey}]]",
            },
            queryParams: {},
            contentType: "application/json",
            body: "",
          },
          preRequest: { propertyInstructions: [] },
          postResponse: {
            propertyInstructions: [],
            outputBuildInstructions: [],
            qrBuildInstructions: [],
          },
        },
      ],
    });
  }),

  // LangChain mock data
  http.get("*/llmstore/llms/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      tasks: [
        {
          actions: ["help", "chat"],
          id: "main-chat",
          type: "openai",
          description: "Main AI assistant task",
          parameters: {
            systemMessage: "You are a helpful assistant.",
            addToOutput: "true",
            logSizeLimit: "20",
          },
          enableBuiltInTools: true,
          builtInToolsWhitelist: ["calculator", "datetime"],
          tools: [
            "eddi://ai.labs.apicalls/apicallstore/apicalls/weather?version=1",
          ],
          enableHttpCallTools: true,
          enableMcpCallTools: true,
          conversationHistoryLimit: 10,
          maxBudgetPerConversation: 1.0,
          enableCostTracking: true,
          enableToolCaching: true,
          enableRateLimiting: true,
          defaultRateLimit: 100,
          a2aAgents: [
            {
              url: "https://remote.example.com/a2a/agents/support",
              name: "Support Agent",
              timeoutMs: 30000,
            },
          ],
          modelCascade: {
            enabled: true,
            strategy: "cascade",
            evaluationStrategy: "structured_output",
            enableInAgentMode: true,
            steps: [
              { type: "openai", parameters: { model: "gpt-5.4-mini" }, confidenceThreshold: 0.7, timeoutMs: 10000 },
              { type: "openai", parameters: { model: "gpt-5.4" }, confidenceThreshold: null, timeoutMs: 30000 },
            ],
          },
          retry: {
            maxAttempts: 3,
            backoffDelayMs: 1000,
            backoffMultiplier: 2.0,
            maxBackoffDelayMs: 10000,
          },
          preRequest: {
            propertyInstructions: [
              { name: "userContext", valueString: "[[${memory.current.input}]]", scope: "step", override: true },
            ],
          },
          postResponse: {
            outputBuildInstructions: [
              {
                iterationObjectName: "obj",
                templateFilterExpression: "",
                outputType: "text",
                outputValue: "{aiOutput.htmlResponseText}",
                httpCodeValidator: {},
              },
            ],
            qrBuildInstructions: [
              {
                pathToTargetArray: "aiOutput.quickReplies",
                iterationObjectName: "obj",
                templateFilterExpression: "",
                quickReplyValue: "{obj.value}",
                quickReplyExpressions: "{obj.expressions}",
                httpCodeValidator: {},
              },
            ],
          },
        },
      ],
    });
  }),

  // Output mock data
  http.get("*/outputstore/outputsets/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      lang: "en",
      outputSet: [
        {
          action: "greet",
          timesOccurred: 0,
          outputs: [
            {
              valueAlternatives: [
                { type: "text", text: "Hello! How can I help you?", delay: 0 },
                { type: "text", text: "Hi there! What can I do for you?", delay: 0 },
              ],
            },
          ],
          quickReplies: [
            { value: "Tell me more", expressions: "more_info", isDefault: false },
            { value: "Goodbye", expressions: "bye", isDefault: false },
          ],
        },
      ],
    });
  }),

  // Property setter mock data
  http.get("*/propertysetterstore/propertysetters/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      setOnActions: [
        {
          actions: ["greet"],
          setProperties: [
            { name: "user_greeted", valueString: "true", scope: "conversation", override: true },
            { name: "greeting_count", valueString: "[[${greeting_count}]] + 1", scope: "longTerm", fromObjectPath: "", override: true },
          ],
        },
      ],
    });
  }),

  // Dictionary mock data
  http.get("*/dictionarystore/dictionaries/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      lang: "en",
      words: [
        { word: "hello", expressions: "greeting(hello)", frequency: 0 },
        { word: "hi", expressions: "greeting(hi)", frequency: 0 },
      ],
      phrases: [
        { phrase: "good morning", expressions: "greeting(good_morning)" },
      ],
      regExs: [
        { regEx: "\\d{5}", expressions: "zipcode(zipcode)" },
      ],
    });
  }),

  // MCP tool discovery endpoint
  http.get("*/mcpcallsstore/mcpcalls/discover-tools", ({ request }) => {
    const url = new URL(request.url);
    const serverUrl = url.searchParams.get("url");
    if (!serverUrl) {
      return HttpResponse.json({ error: "url parameter is required" }, { status: 400 });
    }
    return HttpResponse.json({
      tools: [
        { name: "search_documents", description: "Search indexed documents by query" },
        { name: "index_document", description: "Index a new document into the knowledge base" },
        { name: "delete_document", description: "Delete a document by its unique ID" },
      ],
      count: 3,
    });
  }),

  // MCP Calls mock data
  http.get("*/mcpcallsstore/mcpcalls/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      name: "Document Tools Server",
      mcpServerUrl: "http://localhost:7070/mcp",
      transport: "http",
      apiKey: "${vault:mcp-doc-key}",
      timeoutMs: 30000,
      toolsWhitelist: ["search_documents", "index_document"],
      toolsBlacklist: [],
      mcpCalls: [
        {
          name: "searchDocs",
          actions: ["search"],
          toolName: "search_documents",
          toolArguments: { query: "[[${memory.input}]]" },
          saveResponse: true,
          responseObjectName: "searchResults",
        },
      ],
    });
  }),

  // RAG Knowledge Base mock data (BEFORE generic handlers)
  http.get("*/ragstore/rags/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      name: "product-docs",
      embeddingProvider: "openai",
      embeddingParameters: {
        model: "text-embedding-3-small",
        apiKey: "${vault:openai-key}",
      },
      storeType: "pgvector",
      storeParameters: {
        host: "localhost",
        port: "5432",
        database: "eddi",
        table: "embeddings",
        user: "${vault:pg-user}",
        password: "${vault:pg-password}",
      },
      chunkStrategy: "recursive",
      chunkSize: 512,
      chunkOverlap: 64,
      maxResults: 5,
      minScore: 0.6,
    });
  }),

  // RAG ingestion endpoints (mock)
  http.post("*/ragstore/rags/:id/ingest", () => {
    return HttpResponse.json({
      ingestionId: `ingest-${Date.now()}`,
    });
  }),

  http.get("*/ragstore/rags/:id/ingestion/:ingestionId/status", () => {
    return HttpResponse.json({
      status: "completed",
    });
  }),

  // --- Group Store Mock Handlers ---
  http.get("*/groupstore/groups/descriptors", () => {
    return HttpResponse.json([
      {
        resource: "eddi://ai.labs.group/groupstore/groups/grp1?version=1",
        name: "Product Review Panel",
        description: "Peer-review discussion for product decisions",
        createdOn: Date.now() - 86400000,
        lastModifiedOn: Date.now(),
      },
      {
        resource: "eddi://ai.labs.group/groupstore/groups/grp2?version=1",
        name: "Strategy Debate",
        description: "Devil's advocate debate on business strategy",
        createdOn: Date.now() - 172800000,
        lastModifiedOn: Date.now() - 3600000,
      },
      {
        resource: "eddi://ai.labs.group/groupstore/groups/grp3?version=2",
        name: "Research Round Table",
        description: "Open discussion for research synthesis",
        createdOn: Date.now() - 259200000,
        lastModifiedOn: Date.now() - 7200000,
      },
    ]);
  }),

  http.get("*/groupstore/groups/styles", () => {
    return HttpResponse.json({
      ROUND_TABLE: { label: "Round Table", phases: ["OPINION", "SYNTHESIS"] },
      PEER_REVIEW: { label: "Peer Review", phases: ["OPINION", "CRITIQUE", "REVISION", "SYNTHESIS"] },
      DEVIL_ADVOCATE: { label: "Devil's Advocate", phases: ["OPINION", "CHALLENGE", "DEFENSE", "SYNTHESIS"] },
      DELPHI: { label: "Delphi", phases: ["OPINION", "REVISION", "SYNTHESIS"] },
      DEBATE: { label: "Debate", phases: ["ARGUE", "REBUTTAL", "SYNTHESIS"] },
      CUSTOM: { label: "Custom", phases: [] },
    });
  }),

  http.get("*/groupstore/groups/jsonSchema", () => {
    return HttpResponse.json({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      title: "AgentGroupConfiguration",
      properties: {
        name: { type: "string", description: "Group name" },
        description: { type: "string", description: "Group description" },
        members: { type: "array", items: { type: "object" }, description: "Group members" },
        moderatorAgentId: { type: "string", description: "Moderator agent ID" },
        style: { type: "string", description: "Discussion style" },
        maxRounds: { type: "integer", description: "Maximum rounds" },
      },
    });
  }),

  http.get("*/groupstore/groups/:id", () => {
    return HttpResponse.json({
      name: "Product Review Panel",
      description: "Peer-review discussion for product decisions",
      members: [
        { agentId: "agent1", displayName: "Support Agent", speakingOrder: 1, role: "Reviewer", memberType: "AGENT" },
        { agentId: "agent2", displayName: "FAQ Agent", speakingOrder: 2, role: "Critic", memberType: "AGENT" },
      ],
      moderatorAgentId: "agent1",
      style: "PEER_REVIEW",
      maxRounds: 3,
      phases: [
        { name: "Initial Opinions", type: "OPINION", participants: "*", turnOrder: "SEQUENTIAL", contextScope: "NONE", targetEachPeer: false, inputTemplate: null, repeats: 1 },
        { name: "Critique", type: "CRITIQUE", participants: "*", turnOrder: "SEQUENTIAL", contextScope: "FULL", targetEachPeer: true, inputTemplate: null, repeats: 1 },
        { name: "Synthesis", type: "SYNTHESIS", participants: "moderator", turnOrder: "SEQUENTIAL", contextScope: "FULL", targetEachPeer: false, inputTemplate: null, repeats: 1 },
      ],
      protocol: {
        agentTimeoutSeconds: 60,
        onAgentFailure: "SKIP",
        maxRetries: 2,
        onMemberUnavailable: "SKIP",
      },
    });
  }),

  http.post("*/groupstore/groups", () => {
    const newId = `grp-${Date.now()}`;
    return new HttpResponse(null, {
      status: 201,
      headers: {
        Location: `eddi://ai.labs.group/groupstore/groups/${newId}?version=1`,
      },
    });
  }),

  http.put("*/groupstore/groups/:id", ({ request, params }) => {
    const url = new URL(request.url);
    const currentVersion = parseInt(url.searchParams.get("version") ?? "1", 10);
    return new HttpResponse(null, {
      status: 200,
      headers: {
        Location: `eddi://ai.labs.group/groupstore/groups/${params.id}?version=${currentVersion + 1}`,
      },
    });
  }),

  http.delete("*/groupstore/groups/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Group conversations
  http.get("*/groups/:groupId/conversations", () => {
    return HttpResponse.json([]);
  }),

  http.post("*/groups/:groupId/conversations", ({ params }) => {
    return HttpResponse.json({
      id: `gc-${Date.now()}`,
      groupId: params.groupId,
      userId: "manager-user",
      state: "IN_PROGRESS",
      originalQuestion: "What should we prioritize for Q2?",
      transcript: [],
      memberConversationIds: {},
      currentPhaseIndex: 0,
      currentPhaseName: "Initial Opinions",
      synthesizedAnswer: null,
      depth: 0,
      created: new Date().toISOString(),
      lastModified: new Date().toISOString(),
    });
  }),

  // --- Schedule Store Mock Handlers ---
  http.get("*/schedulestore/schedules", () => {
    return HttpResponse.json([
      {
        id: "sched-1",
        name: "Daily Health Check",
        triggerType: "CRON",
        agentId: "agent1",
        agentVersion: 0,
        environment: "production",
        cronExpression: "0 9 * * MON-FRI",
        cronDescription: "At 09:00 AM, Monday through Friday",
        message: "Run daily health check",
        conversationStrategy: "new",
        enabled: true,
        nextFire: Date.now() + 43200000,
        lastFired: Date.now() - 43200000,
        fireStatus: "COMPLETED",
        failCount: 0,
        timeZone: "UTC",
        createdAt: Date.now() - 604800000,
        updatedAt: Date.now() - 43200000,
      },
      {
        id: "sched-2",
        name: "Heartbeat Monitor",
        triggerType: "HEARTBEAT",
        agentId: "agent2",
        agentVersion: 1,
        environment: "production",
        heartbeatIntervalSeconds: 300,
        message: "Heartbeat ping",
        conversationStrategy: "persistent",
        persistentConversationId: "conv-heartbeat-001",
        enabled: true,
        nextFire: Date.now() + 1800000,
        lastFired: Date.now() - 1800000,
        fireStatus: "PENDING",
        failCount: 0,
        createdAt: Date.now() - 2592000000,
        updatedAt: Date.now() - 1800000,
      },
      {
        id: "sched-3",
        name: "Failed Report",
        triggerType: "CRON",
        agentId: "agent1",
        agentVersion: 0,
        environment: "production",
        cronExpression: "0 8 * * 1",
        cronDescription: "Every Monday at 8:00 AM",
        message: "Generate weekly summary report",
        conversationStrategy: "new",
        enabled: false,
        lastFired: Date.now() - 604800000,
        fireStatus: "DEAD_LETTERED",
        failCount: 3,
        timeZone: "Europe/Vienna",
        createdAt: Date.now() - 2592000000,
        updatedAt: Date.now() - 604800000,
      },
    ]);
  }),

  http.get("*/schedulestore/schedules/admin/failed", () => {
    return HttpResponse.json([
      {
        id: "fire-fail-1",
        scheduleId: "sched-3",
        scheduleName: "Failed Report",
        agentId: "agent1",
        firedAt: Date.now() - 604800000,
        success: false,
        error: "Agent not deployed in production environment",
      },
    ]);
  }),

  http.get("*/schedulestore/schedules/:id", () => {
    return HttpResponse.json({
      id: "sched-1",
      name: "Daily Health Check",
      triggerType: "CRON",
      agentId: "agent1",
      agentVersion: 0,
      environment: "production",
      cronExpression: "0 9 * * MON-FRI",
      cronDescription: "At 09:00 AM, Monday through Friday",
      message: "Run daily health check",
      conversationStrategy: "new",
      enabled: true,
      nextFire: Date.now() + 43200000,
      lastFired: Date.now() - 43200000,
      fireStatus: "COMPLETED",
      failCount: 0,
      timeZone: "UTC",
      createdAt: Date.now() - 604800000,
      updatedAt: Date.now() - 43200000,
    });
  }),

  http.post("*/schedulestore/schedules", () => {
    return new HttpResponse(null, {
      status: 201,
      headers: { Location: `/schedulestore/schedules/sched-${Date.now()}` },
    });
  }),

  http.put("*/schedulestore/schedules/:id", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.delete("*/schedulestore/schedules/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  http.post("*/schedulestore/schedules/:id/enable", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.post("*/schedulestore/schedules/:id/disable", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.post("*/schedulestore/schedules/:id/fire", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.post("*/schedulestore/schedules/:id/retry", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.post("*/schedulestore/schedules/:id/dismiss", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.get("*/schedulestore/schedules/:id/fires", () => {
    return HttpResponse.json([
      {
        id: "fire-1",
        scheduleId: "sched-1",
        scheduleName: "Daily Health Check",
        agentId: "agent1",
        conversationId: "conv-fire-001",
        firedAt: Date.now() - 43200000,
        completedAt: Date.now() - 43195000,
        durationMs: 5000,
        success: true,
      },
      {
        id: "fire-2",
        scheduleId: "sched-1",
        scheduleName: "Daily Health Check",
        agentId: "agent1",
        conversationId: "conv-fire-002",
        firedAt: Date.now() - 129600000,
        completedAt: Date.now() - 129594000,
        durationMs: 6000,
        success: true,
      },
    ]);
  }),

  // Prompt Snippets mock data
  http.get("*/snippetstore/snippets/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      name: "cautious_mode",
      category: "governance",
      description: "Makes the agent more careful and hedging in responses",
      content: "You should be cautious and careful in your responses. When uncertain about facts, explicitly state your uncertainty. Avoid making definitive claims without supporting evidence. Use hedging language when appropriate.",
      tags: ["safety", "production", "enterprise"],
      templateEnabled: true,
    });
  }),

  // Generic descriptor handlers for all resource types
  ...createResourceHandlers("rulestore", "rulesets", "rules"),
  ...createResourceHandlers("apicallstore", "apicalls", "apicalls"),
  ...createResourceHandlers("outputstore", "outputsets", "output"),
  ...createResourceHandlers(
    "dictionarystore",
    "dictionaries",
    "dictionary"
  ),
  ...createResourceHandlers("llmstore", "llms", "llm"),
  ...createResourceHandlers(
    "propertysetterstore",
    "propertysetters",
    "propertysetter"
  ),
  ...createResourceHandlers("mcpcallsstore", "mcpcalls", "mcpcalls"),
  ...createResourceHandlers("ragstore", "rags", "rag"),
  ...createResourceHandlers("parserstore", "parsers", "parser"),
  ...createResourceHandlers("snippetstore", "snippets", "snippets"),
];

function createResourceHandlers(
  store: string,
  plural: string,
  label: string
) {
  // Per-type descriptors with meaningful names
  const descriptorsByType: Record<string, { id: string; name: string; desc: string }[]> = {
    rules: [
      { id: "beh1", name: "Intent Classification Rules", desc: "Routes user input to intents based on expression patterns" },
      { id: "beh2", name: "Escalation Rules", desc: "Detects frustration signals and triggers live-agent handoff" },
      { id: "beh3", name: "Fallback Handler", desc: "Catches unrecognized input and offers guided alternatives" },
    ],
    apicalls: [
      { id: "hc1", name: "Weather API Integration", desc: "Fetches current weather from OpenWeatherMap for any city" },
      { id: "hc2", name: "Payment Gateway", desc: "Stripe payment intent creation and status check" },
      { id: "hc3", name: "CRM Lookup", desc: "Queries Salesforce for customer account details by email" },
      { id: "hc4", name: "Email Notification Service", desc: "Sends transactional emails via SendGrid API" },
    ],
    output: [
      { id: "out1", name: "English Responses", desc: "Standard conversational responses in English" },
      { id: "out2", name: "German Responses", desc: "Localized German output set for DACH market" },
      { id: "out3", name: "Quick Reply Templates", desc: "Pre-built quick reply options for common intents" },
    ],
    dictionary: [
      { id: "dict1", name: "English Intent Dictionary", desc: "Core NLP expressions for greetings, farewells, and common queries" },
      { id: "dict2", name: "Medical Terminology", desc: "Symptom and condition phrases for healthcare triage scenarios" },
      { id: "dict3", name: "Financial Glossary", desc: "Banking and investment terms for financial advisor agents" },
    ],
    llm: [
      { id: "llm1", name: "GPT-5.4 Support Config", desc: "OpenAI GPT-5.4 with tool calling enabled for customer support" },
      { id: "llm2", name: "Claude Analysis Config", desc: "Anthropic Claude for document analysis and summarization" },
      { id: "llm3", name: "Gemini Creative Writing", desc: "Google Gemini 2.5 Flash for creative content generation" },
    ],
    propertysetter: [
      { id: "ps1", name: "Session Tracker", desc: "Persists user session context: language, timezone, last topic" },
      { id: "ps2", name: "User Profile Builder", desc: "Extracts and stores user preferences from conversation history" },
      { id: "ps3", name: "Context Enrichment", desc: "Adds metadata (channel, device, region) to conversation memory" },
    ],
    mcpcalls: [
      { id: "mcp1", name: "Document Search Server", desc: "MCP server providing semantic search over enterprise documents" },
      { id: "mcp2", name: "Calendar Integration", desc: "Google Calendar read/write via MCP for appointment scheduling" },
    ],
    rag: [
      { id: "rag1", name: "Product Knowledge Base", desc: "Vector store of 10k product descriptions with pgvector embeddings" },
      { id: "rag2", name: "Legal Document Store", desc: "Contract clauses and regulatory texts for compliance review" },
      { id: "rag3", name: "Employee Handbook", desc: "HR policies, benefits info, and onboarding procedures" },
    ],
    parser: [
      { id: "par1", name: "Default Parser", desc: "Standard expression parser" },
    ],
    snippets: [
      { id: "snip1", name: "Cautious Mode", desc: "Makes the agent more careful and hedging in responses" },
      { id: "snip2", name: "Compliance Disclaimer", desc: "Adds regulatory compliance disclaimers to financial advice" },
      { id: "snip3", name: "Friendly Persona", desc: "Sets a warm, approachable conversational tone" },
    ],
  };

  const items = descriptorsByType[label] ?? [
    { id: "res1", name: `${label} Config 1`, desc: `First ${label} configuration` },
    { id: "res2", name: `${label} Config 2`, desc: `Second ${label} configuration` },
  ];

  const mockDescriptors = items.map((item, i) => ({
    resource: `eddi://ai.labs.${label}/${store}/${plural}/${item.id}?version=1`,
    name: item.name,
    description: item.desc,
    createdOn: Date.now() - (items.length - i) * 3 * 86400000,
    lastModifiedOn: Date.now() - i * 12 * 3600000,
  }));

  return [
    // JSON Schema endpoint
    http.get(`*/${store}/${plural}/jsonSchema`, () => {
      const schema = RESOURCE_SCHEMAS[label];
      if (schema) {
        return HttpResponse.json(schema);
      }
      return HttpResponse.json({
        $schema: "https://json-schema.org/draft/2020-12/schema",
        type: "object",
        title: `${label}Configuration`,
        properties: {},
      });
    }),
    http.get(`*/${store}/${plural}/descriptors`, ({ request }) => {
      const url = new URL(request.url);
      const includePrevious = url.searchParams.get("includePreviousVersions");
      const filter = url.searchParams.get("filter");

      if (includePrevious === "true" && filter) {
        // Return multiple versions for a specific resource
        return HttpResponse.json([
          {
            resource: `eddi://ai.labs.${label}/${store}/${plural}/${filter}?version=2`,
            name: `${label} Config`,
            description: `${label} configuration`,
            createdOn: Date.now() - 86400000,
            lastModifiedOn: Date.now(),
          },
          {
            resource: `eddi://ai.labs.${label}/${store}/${plural}/${filter}?version=1`,
            name: `${label} Config`,
            description: `${label} configuration`,
            createdOn: Date.now() - 172800000,
            lastModifiedOn: Date.now() - 86400000,
          },
        ]);
      }
      return HttpResponse.json(mockDescriptors);
    }),
    http.get(`*/${store}/${plural}/:id`, () => {
      return HttpResponse.json({ type: label, config: {} });
    }),
    http.post(`*/${store}/${plural}`, () => {
      return new HttpResponse(null, {
        status: 201,
        headers: {
          Location: `/${store}/${plural}/new-res?version=1`,
        },
      });
    }),
    http.put(`*/${store}/${plural}/:id`, ({ request, params }) => {
      const url = new URL(request.url);
      const currentVersion = parseInt(
        url.searchParams.get("version") ?? "1",
        10
      );
      const newVersion = currentVersion + 1;
      return new HttpResponse(null, {
        status: 200,
        headers: {
          Location: `eddi://ai.labs.${label}/${store}/${plural}/${params.id}?version=${newVersion}`,
        },
      });
    }),
    http.delete(`*/${store}/${plural}/:id`, () => {
      return new HttpResponse(null, { status: 204 });
    }),
    // Duplicate resource (POST with :id)
    http.post(`*/${store}/${plural}/:id`, ({ params }) => {
      return new HttpResponse(null, {
        status: 201,
        headers: {
          Location: `/${store}/${plural}/dup-${params.id}?version=1`,
        },
      });
    }),
  ];
}

// --- Coordinator Admin Mock Data ---
const COORDINATOR_STATUS_MOCK = {
  coordinatorType: "nats",
  connected: true,
  connectionStatus: "CONNECTED — nats://eddi-nats:4222 (cluster: eddi-prod)",
  activeConversations: 7,
  totalProcessed: 28_493,
  totalDeadLettered: 5,
  queueDepths: {
    "conv-abc123": 3,
    "conv-def456": 1,
    "conv-ghi789": 2,
    "conv-jkl012": 1,
    "conv-mno345": 1,
    "conv-pqr678": 2,
    "conv-stu901": 1,
  },
};

const DEAD_LETTERS_MOCK = [
  {
    id: "1",
    conversationId: "conv-fail-001",
    error: "Connection timeout to external API",
    timestamp: Date.now() - 3600000,
    payload: '{"conversationId":"conv-fail-001","error":"Connection timeout to external API","timestamp":' + (Date.now() - 3600000) + '}',
  },
  {
    id: "2",
    conversationId: "conv-fail-002",
    error: "LLM rate limit exceeded",
    timestamp: Date.now() - 7200000,
    payload: '{"conversationId":"conv-fail-002","error":"LLM rate limit exceeded","timestamp":' + (Date.now() - 7200000) + '}',
  },
  {
    id: "3",
    conversationId: "conv-fail-003",
    error: "NullPointerException in BehaviorRulesEvaluationTask",
    timestamp: Date.now() - 86400000,
    payload: '{"conversationId":"conv-fail-003","error":"NullPointerException in BehaviorRulesEvaluationTask","timestamp":' + (Date.now() - 86400000) + '}',
  },
];

export const coordinatorHandlers = [
  http.get("*/administration/coordinator/status", () => {
    return HttpResponse.json(COORDINATOR_STATUS_MOCK);
  }),

  http.get("*/administration/coordinator/dead-letters", () => {
    return HttpResponse.json(DEAD_LETTERS_MOCK);
  }),

  http.post("*/administration/coordinator/dead-letters/:entryId/replay", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.delete("*/administration/coordinator/dead-letters/:entryId", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  http.delete("*/administration/coordinator/dead-letters", () => {
    return HttpResponse.json(0);
  }),
];

// ─── Orphan Admin Handlers ───────────────────────────────────────────────────

const ORPHAN_REPORT_MOCK = {
  totalOrphans: 5,
  deletedCount: 0,
  orphans: [
    {
      resourceUri: "eddi://ai.labs.workflow/workflowstore/workflows/orphan1?version=1",
      type: "ai.labs.workflow",
      name: "Legacy Support Workflow (v1)",
      deleted: false,
    },
    {
      resourceUri: "eddi://ai.labs.rules/rulestore/rulesets/orphan2?version=1",
      type: "ai.labs.rules",
      name: "Deprecated Greeting Rules",
      deleted: true,
    },
    {
      resourceUri: "eddi://ai.labs.output/outputstore/outputsets/orphan3?version=2",
      type: "ai.labs.output",
      name: "Archived French Responses",
      deleted: false,
    },
    {
      resourceUri: "eddi://ai.labs.parser/parserstore/parsers/orphan4?version=1",
      type: "ai.labs.parser",
      name: "Test Intent Dictionary",
      deleted: false,
    },
    {
      resourceUri: "eddi://ai.labs.langchain/llmstore/llms/orphan5?version=1",
      type: "ai.labs.langchain",
      name: "Old GPT-3.5 Config",
      deleted: true,
    },
  ],
};

export const orphanHandlers = [
  http.get("*/administration/orphans", () => {
    return HttpResponse.json(ORPHAN_REPORT_MOCK);
  }),

  http.delete("*/administration/orphans", () => {
    return HttpResponse.json({
      ...ORPHAN_REPORT_MOCK,
      deletedCount: ORPHAN_REPORT_MOCK.totalOrphans,
    });
  }),
];

// ─── Log Admin Handlers ──────────────────────────────────────────────────────

const MOCK_LOG_ENTRIES = [
  {
    timestamp: Date.now() - 12000,
    level: "INFO",
    loggerName: "ai.labs.eddi.engine.runtime.AgentEngine",
    message: "Processing conversation conv-abc123 for agent agent1 (v3, production)",
    environment: "production",
    agentId: "agent1",
    agentVersion: 3,
    conversationId: "conv-abc123",
    userId: "user-42",
    instanceId: "eddi-prod-node1",
  },
  {
    timestamp: Date.now() - 10000,
    level: "INFO",
    loggerName: "ai.labs.eddi.modules.llm.impl.LlmTask",
    message: "LLM call completed: model=gpt-5.4, tokens_in=142, tokens_out=89, duration=1240ms, cost=$0.0032",
    environment: "production",
    agentId: "agent1",
    agentVersion: 3,
    conversationId: "conv-abc123",
    userId: "user-42",
    instanceId: "eddi-prod-node1",
  },
  {
    timestamp: Date.now() - 8000,
    level: "WARNING",
    loggerName: "ai.labs.eddi.modules.llm.impl.LlmTask",
    message: "LLM response took 8200ms, exceeding soft timeout of 5000ms. Consider switching to a faster model.",
    environment: "production",
    agentId: "agent4",
    agentVersion: 2,
    conversationId: "conv-def456",
    userId: "user-15",
    instanceId: "eddi-prod-node2",
  },
  {
    timestamp: Date.now() - 6000,
    level: "INFO",
    loggerName: "ai.labs.eddi.engine.schedule.ScheduleFireExecutor",
    message: "Scheduled fire completed: sched-1 'Daily Health Check' → agent1 (strategy=new, cost=$0.0018)",
    environment: "production",
    agentId: "agent1",
    agentVersion: 3,
    conversationId: "conv-sched-001",
    userId: "system:scheduler",
    instanceId: "eddi-prod-node1",
  },
  {
    timestamp: Date.now() - 4500,
    level: "INFO",
    loggerName: "ai.labs.eddi.engine.lifecycle.LifecycleManager",
    message: "Workflow pipeline completed: 4 tasks executed in 1842ms (parser→rules→llm→output)",
    environment: "production",
    agentId: "agent5",
    agentVersion: 1,
    conversationId: "conv-ghi789",
    userId: "user-78",
    instanceId: "eddi-prod-node1",
  },
  {
    timestamp: Date.now() - 3000,
    level: "WARNING",
    loggerName: "ai.labs.eddi.secrets.SecretResolver",
    message: "Secret 'stripe-secret-key' for agent4 has not been rotated in 90+ days",
    environment: "production",
    agentId: "agent4",
    agentVersion: 2,
    conversationId: null,
    userId: null,
    instanceId: "eddi-prod-node2",
  },
  {
    timestamp: Date.now() - 1500,
    level: "SEVERE",
    loggerName: "ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask",
    message:
      "Failed to execute HTTP call 'crm-lookup'\n\tat ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask.executeTask(HttpCallsTask.java:85)\n\tat ai.labs.eddi.engine.lifecycle.LifecycleManager.executeComponent(LifecycleManager.java:120)\nCaused by: java.net.ConnectException: Connection refused (Connection refused)\n\tat java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:565)",
    environment: "production",
    agentId: "agent4",
    agentVersion: 2,
    conversationId: "conv-jkl012",
    userId: "user-33",
    instanceId: "eddi-prod-node2",
  },
  {
    timestamp: Date.now() - 500,
    level: "INFO",
    loggerName: "ai.labs.eddi.engine.audit.AuditLedgerService",
    message: "Audit ledger flushed: 12 entries persisted (batch integrity verified, HMAC OK)",
    environment: "production",
    agentId: null,
    agentVersion: null,
    conversationId: null,
    userId: null,
    instanceId: "eddi-prod-node1",
  },
];

export const logAdminHandlers = [
  http.get("*/administration/logs", () => {
    return HttpResponse.json(MOCK_LOG_ENTRIES);
  }),

  http.get("*/administration/logs/history", () => {
    return HttpResponse.json(
      MOCK_LOG_ENTRIES.map((e) => ({
        ...e,
        timestamp: new Date(e.timestamp).toISOString(),
      }))
    );
  }),

  http.get("*/administration/logs/instance-id", () => {
    return HttpResponse.json({ instanceId: "eddi-host-a1b2" });
  }),
];

// --- Secrets Vault Mock ---
const MOCK_SECRETS = [
  {
    tenantId: "default",
    keyName: "openai-api-key",
    createdAt: new Date(Date.now() - 12 * 86400000).toISOString(),
    lastAccessedAt: new Date(Date.now() - 300000).toISOString(),
    lastRotatedAt: new Date(Date.now() - 5 * 86400000).toISOString(),
    checksum: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
    description: "OpenAI API key for production agents",
    allowedAgents: ["*"],
  },
  {
    tenantId: "default",
    keyName: "sendgrid-api-key",
    createdAt: new Date(Date.now() - 10 * 86400000).toISOString(),
    lastAccessedAt: new Date(Date.now() - 2 * 3600000).toISOString(),
    lastRotatedAt: null,
    checksum: "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5",
    description: "SendGrid email service key",
    allowedAgents: ["*"],
  },
  {
    tenantId: "default",
    keyName: "anthropic-api-key",
    createdAt: new Date(Date.now() - 8 * 86400000).toISOString(),
    lastAccessedAt: new Date(Date.now() - 86400000).toISOString(),
    lastRotatedAt: null,
    checksum: "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
    description: "Anthropic Claude API key",
    allowedAgents: ["*"],
  },
  {
    tenantId: "default",
    keyName: "stripe-secret-key",
    createdAt: new Date(Date.now() - 6 * 86400000).toISOString(),
    lastAccessedAt: null,
    lastRotatedAt: null,
    checksum: "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5",
    description: "Stripe payment processing secret",
    allowedAgents: ["agent4"],
  },
  {
    tenantId: "default",
    keyName: "pg-connection-string",
    createdAt: new Date(Date.now() - 3 * 86400000).toISOString(),
    lastAccessedAt: new Date(Date.now() - 7200000).toISOString(),
    lastRotatedAt: new Date(Date.now() - 2 * 86400000).toISOString(),
    checksum: "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3",
    description: "PostgreSQL vector store connection string",
    allowedAgents: ["*"],
  },
];

export const secretsHandlers = [
  // List secrets (tenant-scoped, no agentId)
  http.get("*/secretstore/secrets/:tenantId", ({ params, request }) => {
    // Skip the health endpoint
    if (params.tenantId === "health") return;
    const url = new URL(request.url);
    // Skip paths like /tenantId/keyName (those are getMetadata)
    const segments = url.pathname.split("/").filter(Boolean);
    if (segments.length > 3) return;
    const filtered = MOCK_SECRETS.filter(
      (s) => s.tenantId === params.tenantId,
    );
    return HttpResponse.json(filtered);
  }),

  // Store secret (tenant-scoped)
  http.put("*/secretstore/secrets/:tenantId/:keyName", ({ params }) => {
    const tenantId = params.tenantId as string;
    const keyName = params.keyName as string;
    const ref = tenantId === "default"
      ? `\${eddivault:${keyName}}`
      : `\${eddivault:${tenantId}/${keyName}}`;
    return HttpResponse.json(
      {
        reference: ref,
        tenantId,
        keyName,
      },
      { status: 201 },
    );
  }),

  // Delete secret (tenant-scoped)
  http.delete(
    "*/secretstore/secrets/:tenantId/:keyName",
    () => new HttpResponse(null, { status: 204 }),
  ),

  // Health check
  http.get("*/secretstore/secrets/health", () =>
    HttpResponse.json({ status: "UP", provider: "VaultSecretProvider", available: true }),
  ),

  // Rotate secret
  http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", ({ params }) => {
    const tenantId = params.tenantId as string;
    const keyName = params.keyName as string;
    const ref = tenantId === "default"
      ? `\${eddivault:${keyName}}`
      : `\${eddivault:${tenantId}/${keyName}}`;
    return HttpResponse.json(
      { reference: ref, tenantId, keyName },
      { status: 200 },
    );
  }),
];

// ─── Audit Trail Handlers ────────────────────────────────────────────────────

const MOCK_AUDIT_ENTRIES = [
  {
    id: "audit-1",
    conversationId: "conv1",
    agentId: "agent1",
    agentVersion: 1,
    userId: "user-1",
    environment: "production",
    stepIndex: 0,
    taskId: "ai.labs.parser",
    taskType: "expressions",
    taskIndex: 0,
    durationMs: 12,
    input: { "input:initial": "Hello there" },
    output: { "expressions:parsed": ["greeting(hello)"] },
    llmDetail: null,
    toolCalls: null,
    actions: null,
    cost: 0,
    timestamp: new Date(Date.now() - 60000).toISOString(),
    hmac: "a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef12345678",
  },
  {
    id: "audit-2",
    conversationId: "conv1",
    agentId: "agent1",
    agentVersion: 1,
    userId: "user-1",
    environment: "production",
    stepIndex: 0,
    taskId: "ai.labs.rules",
    taskType: "behavior",
    taskIndex: 1,
    durationMs: 3,
    input: { "expressions:parsed": ["greeting(hello)"] },
    output: { "actions:triggered": ["greet", "chat"] },
    llmDetail: null,
    toolCalls: null,
    actions: ["greet", "chat"],
    cost: 0,
    timestamp: new Date(Date.now() - 59000).toISOString(),
    hmac: "b2c3d4e5f6a1789012345678901234567890abcdef1234567890abcdef12345678",
  },
  {
    id: "audit-3",
    conversationId: "conv1",
    agentId: "agent1",
    agentVersion: 1,
    userId: "user-1",
    environment: "production",
    stepIndex: 0,
    taskId: "ai.labs.llm",
    taskType: "langchain",
    taskIndex: 2,
    durationMs: 1850,
    input: { "user:message": "Hello there", "conversation:history": 3 },
    output: { "llm:response": "Hi there! How can I help you today?" },
    llmDetail: {
      "compiled_prompt": "You are a helpful assistant.\n\nUser: Hello there",
      "model_response": "Hi there! How can I help you today?",
      "model_name": "gpt-5.4-mini",
      "input_tokens": 42,
      "output_tokens": 12,
    },
    toolCalls: null,
    actions: ["greet", "chat"],
    cost: 0.003,
    timestamp: new Date(Date.now() - 57000).toISOString(),
    hmac: "c3d4e5f6a1b2789012345678901234567890abcdef1234567890abcdef12345678",
  },
  {
    id: "audit-4",
    conversationId: "conv1",
    agentId: "agent1",
    agentVersion: 1,
    userId: "user-1",
    environment: "production",
    stepIndex: 0,
    taskId: "ai.labs.output",
    taskType: "output",
    taskIndex: 3,
    durationMs: 2,
    input: { "actions": ["greet", "chat"] },
    output: { "output:text": "Hi there! How can I help you today?" },
    llmDetail: null,
    toolCalls: null,
    actions: ["greet", "chat"],
    cost: 0,
    timestamp: new Date(Date.now() - 55000).toISOString(),
    hmac: "d4e5f6a1b2c3789012345678901234567890abcdef1234567890abcdef12345678",
  },
];

export const auditHandlers = [
  // Get audit trail by conversation
  http.get("*/auditstore/:conversationId/count", () => {
    return HttpResponse.json(MOCK_AUDIT_ENTRIES.length);
  }),

  http.get("*/auditstore/agent/:agentId", () => {
    return HttpResponse.json(MOCK_AUDIT_ENTRIES);
  }),

  http.get("*/auditstore/:conversationId", ({ params }) => {
    if (params.conversationId === "count") return;
    return HttpResponse.json(MOCK_AUDIT_ENTRIES);
  }),
];

// --- Tenant Quota Mock Data ---

const MOCK_QUOTA = {
  tenantId: "default",
  maxConversationsPerDay: 1000,
  maxAgentsPerTenant: 50,
  maxApiCallsPerMinute: 120,
  maxMonthlyCostUsd: 500.00,
  enabled: true,
};

const MOCK_USAGE = {
  tenantId: "default",
  conversationsToday: 847,
  apiCallsThisMinute: 98,
  monthlyCostUsd: 387.42,
  minuteWindowStart: new Date().toISOString(),
  dayStart: new Date(new Date().setHours(0, 0, 0, 0)).toISOString(),
};

export const quotaHandlers = [
  http.get("*/administration/quotas", () => {
    return HttpResponse.json([MOCK_QUOTA]);
  }),

  http.get("*/administration/quotas/:tenantId/usage", () => {
    return HttpResponse.json(MOCK_USAGE);
  }),

  http.get("*/administration/quotas/:tenantId", () => {
    return HttpResponse.json(MOCK_QUOTA);
  }),

  http.put("*/administration/quotas/:tenantId", async ({ request }) => {
    const body = await request.json();
    return HttpResponse.json(body);
  }),

  http.post("*/administration/quotas/:tenantId/usage/reset", () => {
    return new HttpResponse(null, { status: 200 });
  }),
];

// ─── Schedule Handlers ───────────────────────────────────────────────────────

const SCHEDULES_MOCK = [
  {
    id: "sched-1",
    name: "Daily Health Check",
    triggerType: "CRON",
    agentId: "agent1",
    agentVersion: 0,
    environment: "production",
    cronExpression: "0 9 * * MON-FRI",
    cronDescription: "At 09:00 AM, Monday through Friday",
    message: "health_check",
    conversationStrategy: "new",
    enabled: true,
    nextFire: Date.now() + 3600000,
    lastFired: Date.now() - 86400000,
    fireStatus: "COMPLETED",
    failCount: 0,
    createdAt: Date.now() - 604800000,
    updatedAt: Date.now() - 86400000,
  },
  {
    id: "sched-2",
    name: "Heartbeat Monitor",
    triggerType: "HEARTBEAT",
    agentId: "agent2",
    agentVersion: 0,
    environment: "production",
    heartbeatIntervalSeconds: 300,
    message: "ping",
    conversationStrategy: "persistent",
    enabled: true,
    nextFire: Date.now() + 60000,
    lastFired: Date.now() - 300000,
    fireStatus: "PENDING",
    failCount: 0,
    createdAt: Date.now() - 172800000,
    updatedAt: Date.now() - 300000,
  },
  {
    id: "sched-3",
    name: "Failed Report",
    triggerType: "CRON",
    agentId: "agent1",
    agentVersion: 0,
    environment: "production",
    cronExpression: "*/5 * * * *",
    cronDescription: "Every 5 minutes",
    message: "generate_report",
    conversationStrategy: "new",
    enabled: false,
    nextFire: null,
    lastFired: Date.now() - 7200000,
    fireStatus: "DEAD_LETTERED",
    failCount: 3,
    createdAt: Date.now() - 259200000,
    updatedAt: Date.now() - 7200000,
  },
];

const FIRE_LOGS_MOCK = [
  {
    id: "fire-1",
    scheduleId: "sched-1",
    scheduleName: "Daily Health Check",
    agentId: "agent1",
    conversationId: "conv-123",
    firedAt: Date.now() - 86400000,
    completedAt: Date.now() - 86399000,
    durationMs: 1000,
    success: true,
    error: null,
  },
  {
    id: "fire-2",
    scheduleId: "sched-1",
    scheduleName: "Daily Health Check",
    agentId: "agent1",
    conversationId: "conv-124",
    firedAt: Date.now() - 172800000,
    completedAt: Date.now() - 172799500,
    durationMs: 500,
    success: false,
    error: "Connection timeout",
  },
];

export const scheduleHandlers = [
  // List all schedules
  http.get("*/schedulestore/schedules", ({ request }) => {
    const url = new URL(request.url);
    const agentId = url.searchParams.get("agentId");
    if (agentId) {
      return HttpResponse.json(SCHEDULES_MOCK.filter((s) => s.agentId === agentId));
    }
    return HttpResponse.json(SCHEDULES_MOCK);
  }),

  // Get single schedule
  http.get("*/schedulestore/schedules/:id", ({ params, request }) => {
    const url = new URL(request.url);
    // Skip sub-paths like /fires, /enable, etc.
    if (url.pathname.includes("/fires") || url.pathname.includes("/admin")) return;
    const schedule = SCHEDULES_MOCK.find((s) => s.id === params.id);
    if (schedule) return HttpResponse.json(schedule);
    return new HttpResponse(null, { status: 404 });
  }),

  // Create schedule
  http.post("*/schedulestore/schedules", () => {
    return new HttpResponse(null, {
      status: 201,
      headers: { Location: "/schedulestore/schedules/new-sched-1" },
    });
  }),

  // Update schedule
  http.put("*/schedulestore/schedules/:id", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Delete schedule
  http.delete("*/schedulestore/schedules/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Enable
  http.post("*/schedulestore/schedules/:id/enable", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Disable
  http.post("*/schedulestore/schedules/:id/disable", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Fire now
  http.post("*/schedulestore/schedules/:id/fire", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Fire logs
  http.get("*/schedulestore/schedules/:id/fires", () => {
    return HttpResponse.json(FIRE_LOGS_MOCK);
  }),

  // Admin - failed fires
  http.get("*/schedulestore/schedules/admin/failed", () => {
    return HttpResponse.json(FIRE_LOGS_MOCK.filter((l) => !l.success));
  }),

  // Retry dead letter
  http.post("*/schedulestore/schedules/:id/retry", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Dismiss dead letter
  http.post("*/schedulestore/schedules/:id/dismiss", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // --- Agent Setup Wizard ---
  http.post("*/administration/agents/setup", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json(
      {
        action: "setup_complete",
        agentId: `agent-${Date.now()}`,
        agentName: body.name ?? "New Agent",
        provider: body.provider ?? "anthropic",
        model: body.model ?? "claude-sonnet-4-6",
        deployed: body.deploy !== false,
        deploymentStatus: body.deploy !== false ? "READY" : undefined,
        quickRepliesEnabled: body.enableQuickReplies ?? false,
        sentimentAnalysisEnabled: body.enableSentimentAnalysis ?? false,
        resources: { agentLocation: "/agentstore/agents/mock-agent?version=1" },
      },
      { status: 201 },
    );
  }),

  http.post("*/administration/agents/setup-api", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json(
      {
        action: "api_agent_created",
        agentId: `api-agent-${Date.now()}`,
        agentName: body.name ?? "API Agent",
        provider: body.provider ?? "anthropic",
        model: body.model ?? "claude-sonnet-4-6",
        deployed: body.deploy !== false,
        deploymentStatus: body.deploy !== false ? "READY" : undefined,
        endpointCount: 5,
        groups: ["Users", "Orders"],
        quickRepliesEnabled: body.enableQuickReplies ?? false,
        sentimentAnalysisEnabled: body.enableSentimentAnalysis ?? false,
        resources: { agentLocation: "/agentstore/agents/mock-api-agent?version=1" },
      },
      { status: 201 },
    );
  }),

  // ==========================================
  // === Secrets Vault Mocks ===
  // ==========================================

  // Vault health check (health endpoint is only here, not in secretsHandlers)
  http.get("*/secretstore/secrets/health", () => {
    return HttpResponse.json({
      status: "UP",
      provider: "VaultSecretProvider",
      available: true,
    });
  }),

  // List/Store/Delete secrets are in the secretsHandlers export (used by server.ts)

  // ==========================================
  // === Coordinator Mocks ===
  // ==========================================

  // Coordinator status
  http.get("*/administration/coordinator/status", () => {
    return HttpResponse.json({
      coordinatorType: "nats",
      connected: true,
      connectionStatus: "CONNECTED",
      activeConversations: 3,
      totalProcessed: 1247,
      totalDeadLettered: 2,
      queueDepths: {
        "conv-abc123": 1,
        "conv-def456": 0,
      },
    });
  }),

  // Coordinator dead-letters
  http.get("*/administration/coordinator/dead-letters", () => {
    return HttpResponse.json([
      {
        id: "dl-001",
        conversationId: "conv-failed-1",
        error: "LLM provider timeout after 30s — model gpt-5.4-mini did not respond",
        timestamp: Date.now() - 3600000,
        payload: JSON.stringify({ input: "What is the weather?", agentId: "agent1", step: 2 }),
      },
      {
        id: "dl-002",
        conversationId: "conv-failed-2",
        error: "HttpCallTask failed: 503 Service Unavailable from https://api.weather.com",
        timestamp: Date.now() - 7200000,
        payload: JSON.stringify({ input: "Book a flight", agentId: "agent2", step: 1 }),
      },
    ]);
  }),

  // Replay dead-letter
  http.post("*/administration/coordinator/dead-letters/:id/replay", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Discard dead-letter
  http.delete("*/administration/coordinator/dead-letters/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Purge all dead-letters
  http.delete("*/administration/coordinator/dead-letters", () => {
    return HttpResponse.json(2);
  }),

  // Coordinator SSE stream
  http.get("*/administration/coordinator/stream", () => {
    return new HttpResponse(null, { status: 200, headers: { "Content-Type": "text/event-stream" } });
  }),

  // ==========================================
  // === Audit Trail Mocks ===
  // ==========================================

  // Audit by conversation
  http.get("*/auditstore/:conversationId", ({ request, params }) => {
    const url = new URL(request.url);
    // Don't match /count sub-path
    if (url.pathname.endsWith("/count")) return;
    const convId = params.conversationId as string;
    // Don't match the /agent/ path
    if (convId === "agent") return;
    if (convId === "recent") {
      // Recent entries endpoint
      return HttpResponse.json(generateMockAuditEntries("conv-recent-1", 10));
    }
    return HttpResponse.json(generateMockAuditEntries(convId, 5));
  }),

  // Audit by agent
  http.get("*/auditstore/agent/:agentId", () => {
    return HttpResponse.json(generateMockAuditEntries("conv-from-agent", 8));
  }),

  // Audit count
  http.get("*/auditstore/:conversationId/count", () => {
    return HttpResponse.json(5);
  }),

  // ==========================================
  // === Group Config & Conversation Mocks ===
  // ==========================================

  // Group descriptors
  http.get("*/groupstore/groups/descriptors", () => {
    return HttpResponse.json([
      {
        resource: "eddi://ai.labs.group/groupstore/groups/group1?version=1",
        name: "Advisory Board (Beraterstab)",
        description: "15-member panel for strategic advisory discussions",
        createdOn: Date.now() - 86400000,
        lastModifiedOn: Date.now(),
      },
      {
        resource: "eddi://ai.labs.group/groupstore/groups/group2?version=1",
        name: "Code Review Panel",
        description: "Peer review for architecture decisions",
        createdOn: Date.now() - 172800000,
        lastModifiedOn: Date.now() - 3600000,
      },
    ]);
  }),

  // Group config
  http.get("*/groupstore/groups/:id", ({ request }) => {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/descriptors") || url.pathname.endsWith("/styles") || url.pathname.endsWith("/jsonSchema")) return;
    return HttpResponse.json({
      name: "Advisory Board (Beraterstab)",
      description: "A panel of expert advisors consulting on strategic decisions.",
      members: [
        { agentId: "agent1", displayName: "Marketing Expert", speakingOrder: 1, role: null, memberType: "AGENT" },
        { agentId: "agent2", displayName: "Sales Strategist", speakingOrder: 2, role: null, memberType: "AGENT" },
        { agentId: "agent3", displayName: "Product Manager", speakingOrder: 3, role: null, memberType: "AGENT" },
        { agentId: "agent4", displayName: "Tech Lead", speakingOrder: 4, role: null, memberType: "AGENT" },
        { agentId: "agent5", displayName: "Legal Counsel", speakingOrder: 5, role: null, memberType: "AGENT" },
      ],
      moderatorAgentId: "agent-mod",
      style: "ROUND_TABLE",
      maxRounds: 2,
      phases: null,
      protocol: { agentTimeoutSeconds: 60, onAgentFailure: "SKIP", maxRetries: 2, onMemberUnavailable: "SKIP" },
    });
  }),

  // Discussion styles
  http.get("*/groupstore/groups/styles", () => {
    return HttpResponse.json({
      ROUND_TABLE: { description: "All members share opinions, then discuss, then synthesize", phases: ["OPINION", "ARGUE", "SYNTHESIS"] },
      PEER_REVIEW: { description: "Independent opinions, peer critique, revision, synthesis", phases: ["OPINION", "CRITIQUE", "REVISION", "SYNTHESIS"] },
      DEVIL_ADVOCATE: { description: "Opinions challenged by designated devil's advocate", phases: ["OPINION", "CHALLENGE", "DEFENSE", "SYNTHESIS"] },
      DELPHI: { description: "Anonymous rounds to reduce groupthink", phases: ["OPINION", "REVISION", "SYNTHESIS"] },
      DEBATE: { description: "Formal pro/con debate with rebuttals", phases: ["ARGUE", "REBUTTAL", "SYNTHESIS"] },
    });
  }),

  // Group JSON schema
  http.get("*/groupstore/groups/jsonSchema", () => {
    return HttpResponse.json({ type: "object", properties: { name: { type: "string" }, members: { type: "array" } } });
  }),

  // Create group
  http.post("*/groupstore/groups", () => {
    return new HttpResponse(null, { status: 201, headers: { Location: `/groupstore/groups/new-group-${Date.now()}?version=1` } });
  }),

  // Update group
  http.put("*/groupstore/groups/:id", ({ params }) => {
    return new HttpResponse(null, { status: 200, headers: { Location: `/groupstore/groups/${params.id}?version=2` } });
  }),

  // Duplicate group
  http.post("*/groupstore/groups/:id", () => {
    return new HttpResponse(null, { status: 201, headers: { Location: `/groupstore/groups/dup-${Date.now()}?version=1` } });
  }),

  // Delete group
  http.delete("*/groupstore/groups/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // List group conversations
  http.get("*/groups/:groupId/conversations", ({ request }) => {
    const url = new URL(request.url);
    const pathParts = url.pathname.split("/");
    // Don't match specific conversation GETs (which have an extra path segment)
    if (pathParts.length > 4 && !url.pathname.endsWith("/conversations")) return;
    return HttpResponse.json([
      {
        id: "gconv1",
        groupId: "group1",
        userId: "manager-user",
        state: "COMPLETED",
        originalQuestion: "Should we expand into the European market this quarter?",
        transcript: [],
        memberConversationIds: {},
        currentPhaseIndex: 2,
        currentPhaseName: "Synthesis",
        synthesizedAnswer: "After careful consideration, the panel recommends a phased European market expansion starting in Q3.",
        depth: 0,
        created: new Date(Date.now() - 3600000).toISOString(),
        lastModified: new Date(Date.now() - 1800000).toISOString(),
      },
    ]);
  }),

  // Get single group conversation (detailed transcript)
  http.get("*/groups/:groupId/conversations/:convId", () => {
    const now = new Date();
    return HttpResponse.json({
      id: "gconv1",
      groupId: "group1",
      userId: "manager-user",
      state: "COMPLETED",
      originalQuestion: "Should we expand into the European market this quarter?",
      transcript: [
        { speakerAgentId: "user", speakerDisplayName: "User", content: "Should we expand into the European market this quarter?", phaseIndex: -1, phaseName: null, type: "QUESTION", timestamp: new Date(now.getTime() - 600000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent1", speakerDisplayName: "Marketing Expert", content: "From a marketing perspective, Europe presents significant opportunities. Brand awareness campaigns could leverage our existing digital presence. However, we need to consider GDPR compliance and localization of marketing materials.", phaseIndex: 0, phaseName: "Initial Opinions", type: "OPINION", timestamp: new Date(now.getTime() - 540000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent2", speakerDisplayName: "Sales Strategist", content: "The sales pipeline data suggests strong demand in Germany and France. I recommend starting with these markets due to existing partner relationships. Timeline should be 6–8 months for meaningful traction.", phaseIndex: 0, phaseName: "Initial Opinions", type: "OPINION", timestamp: new Date(now.getTime() - 480000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent3", speakerDisplayName: "Product Manager", content: "Our product needs localization work for European markets — currency support, language packs, and compliance features. I estimate 3 months of engineering effort before we can launch.", phaseIndex: 0, phaseName: "Initial Opinions", type: "OPINION", timestamp: new Date(now.getTime() - 420000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent4", speakerDisplayName: "Tech Lead", content: "Infrastructure-wise, we'd need EU-based data centers for GDPR. AWS eu-west-1 is ready, but we need to provision new clusters. Budget estimate: $50k/month additional hosting.", phaseIndex: 0, phaseName: "Initial Opinions", type: "OPINION", timestamp: new Date(now.getTime() - 360000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent5", speakerDisplayName: "Legal Counsel", content: "GDPR compliance is mandatory and non-trivial. We need a DPO appointment, privacy impact assessments, and updated Terms of Service. I strongly advise against rushing this.", phaseIndex: 0, phaseName: "Initial Opinions", type: "OPINION", timestamp: new Date(now.getTime() - 300000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent1", speakerDisplayName: "Marketing Expert", content: "I agree with Sales on starting with Germany and France. Marketing can run preliminary campaigns while Legal handles compliance. Let's plan for a Q3 soft launch.", phaseIndex: 1, phaseName: "Discussion", type: "ARGUMENT", timestamp: new Date(now.getTime() - 240000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent5", speakerDisplayName: "Legal Counsel", content: "A Q3 timeline is realistic for compliance if we start immediately. I'd recommend engaging a European law firm for local expertise.", phaseIndex: 1, phaseName: "Discussion", type: "ARGUMENT", timestamp: new Date(now.getTime() - 180000).toISOString(), errorReason: null, targetAgentId: null },
        { speakerAgentId: "agent-mod", speakerDisplayName: "Moderator", content: "After careful consideration, the panel recommends a phased European market expansion starting in Q3, with Germany and France as initial targets. Key prerequisites: (1) GDPR compliance and DPO appointment — start immediately, (2) Product localization — 3 month engineering sprint, (3) EU infrastructure provisioning — $50k/month incremental, (4) Local legal counsel engagement. The consensus is to proceed, but not to rush. A well-prepared Q3 soft launch balances opportunity with risk management.", phaseIndex: 2, phaseName: "Synthesis", type: "SYNTHESIS", timestamp: new Date(now.getTime() - 120000).toISOString(), errorReason: null, targetAgentId: null },
      ],
      memberConversationIds: { agent1: "mc1", agent2: "mc2", agent3: "mc3", agent4: "mc4", agent5: "mc5" },
      currentPhaseIndex: 2,
      currentPhaseName: "Synthesis",
      synthesizedAnswer: "After careful consideration, the panel recommends a phased European market expansion starting in Q3, with Germany and France as initial targets. Key prerequisites: (1) GDPR compliance and DPO appointment — start immediately, (2) Product localization — 3 month engineering sprint, (3) EU infrastructure provisioning — $50k/month incremental, (4) Local legal counsel engagement. The consensus is to proceed, but not to rush. A well-prepared Q3 soft launch balances opportunity with risk management.",
      depth: 0,
      created: new Date(now.getTime() - 600000).toISOString(),
      lastModified: new Date(now.getTime() - 120000).toISOString(),
    });
  }),

  // Start group discussion
  http.post("*/groups/:groupId/conversations", () => {
    return HttpResponse.json({
      id: `gconv-${Date.now()}`,
      groupId: "group1",
      userId: "manager-user",
      state: "IN_PROGRESS",
      originalQuestion: "New discussion started",
      transcript: [],
      memberConversationIds: {},
      currentPhaseIndex: 0,
      currentPhaseName: "Initial Opinions",
      synthesizedAnswer: null,
      depth: 0,
      created: new Date().toISOString(),
      lastModified: new Date().toISOString(),
    }, { status: 201 });
  }),

  // SSE stream group discussion
  http.post("*/groups/:groupId/conversations/stream", ({ params }) => {
    const groupId = String(params.groupId);
    const convId = `gconv-stream-${Date.now()}`;

    // Build SSE event string helper
    const sseEvent = (name: string, data: object) =>
      `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

    // Simulate a multi-phase discussion with delays
    const events = [
      sseEvent("group_start", {
        conversationId: convId,
        groupId,
        question: "What are the key benefits of AI agents?",
        style: "ROUND_TABLE",
        phaseCount: 2,
        agentIds: ["agent1", "agent2"],
      }),
      sseEvent("phase_start", { phaseIndex: 0, phaseName: "Initial Opinions", phaseType: "OPINION", participants: "ALL_MEMBERS" }),
      sseEvent("speaker_start", { agentId: "agent1", displayName: "Support Agent", phaseIndex: 0, phaseName: "Initial Opinions" }),
      sseEvent("speaker_complete", {
        agentId: "agent1",
        displayName: "Support Agent",
        content: "AI agents provide 24/7 availability, consistent response quality, and can handle multiple conversations simultaneously. They reduce operational costs while improving customer satisfaction through instant responses.",
        phaseIndex: 0,
        phaseName: "Initial Opinions",
      }),
      sseEvent("speaker_start", { agentId: "agent2", displayName: "FAQ Agent", phaseIndex: 0, phaseName: "Initial Opinions" }),
      sseEvent("speaker_complete", {
        agentId: "agent2",
        displayName: "FAQ Agent",
        content: "From an information management perspective, AI agents excel at maintaining up-to-date knowledge bases, providing consistent answers across all channels, and learning from user interactions to improve over time.",
        phaseIndex: 0,
        phaseName: "Initial Opinions",
      }),
      sseEvent("phase_complete", { phaseIndex: 0, phaseName: "Initial Opinions" }),
      sseEvent("phase_start", { phaseIndex: 1, phaseName: "Synthesis", phaseType: "SYNTHESIS", participants: "MODERATOR_ONLY" }),
      sseEvent("synthesis_start", { moderatorAgentId: "moderator-agent" }),
      sseEvent("speaker_start", { agentId: "moderator-agent", displayName: "Moderator", phaseIndex: 1, phaseName: "Synthesis" }),
      sseEvent("speaker_complete", {
        agentId: "moderator-agent",
        displayName: "Moderator",
        content: "Both agents highlight complementary benefits: operational efficiency (24/7 availability, cost reduction, scalability) and knowledge management (consistent answers, continuous learning, multi-channel support). Together, these benefits make AI agents a compelling solution for modern customer engagement.",
        phaseIndex: 1,
        phaseName: "Synthesis",
      }),
      sseEvent("phase_complete", { phaseIndex: 1, phaseName: "Synthesis" }),
      sseEvent("group_complete", {
        state: "COMPLETED",
        synthesizedAnswer: "Both agents highlight complementary benefits: operational efficiency (24/7 availability, cost reduction, scalability) and knowledge management (consistent answers, continuous learning, multi-channel support). Together, these benefits make AI agents a compelling solution for modern customer engagement.",
      }),
    ];

    // Stream events with simulated delays
    const stream = new ReadableStream({
      async start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) {
          await new Promise((r) => setTimeout(r, 500 + Math.random() * 1000));
          controller.enqueue(encoder.encode(event));
        }
        controller.close();
      },
    });

    return new HttpResponse(stream, {
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        Connection: "keep-alive",
      },
    });
  }),

  // Delete group conversation
  http.delete("*/groups/:groupId/conversations/:convId", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // --- Phase 13: Tool Metrics & Debug Endpoints ---

  // Conversation costs
  http.get("*/llm/tools/costs/conversation/:convId", () => {
    return HttpResponse.json({
      conversationId: "conv-mock",
      totalCost: 0.015,
      totalToolCalls: 7,
      toolUsage: {
        websearch: { calls: 3, totalCost: 0.003 },
        fetch_weather: { calls: 4, totalCost: 0.002 },
      },
    });
  }),

  // Tool rate limit
  http.get("*/llm/tools/ratelimit/:tool", ({ params }) => {
    return HttpResponse.json({
      toolName: params.tool as string,
      limit: 60,
      remaining: 42,
      resetAt: new Date(Date.now() + 60_000).toISOString(),
    });
  }),

  // Cache stats
  http.get("*/llm/tools/cache/stats", () => {
    return HttpResponse.json({
      totalHits: 23,
      totalMisses: 12,
      hitRate: 0.657,
      perToolStats: {
        fetch_weather: { hits: 15, misses: 5 },
        websearch: { hits: 8, misses: 7 },
      },
    });
  }),

  // Tool history
  http.get("*/llm/tools/history/:convId", () => {
    return HttpResponse.json([
      {
        toolName: "fetch_weather",
        args: { city: "Vienna" },
        result: '{"temp": 22, "condition": "sunny"}',
        durationMs: 156,
        cost: 0.0005,
        timestamp: new Date().toISOString(),
      },
      {
        toolName: "websearch",
        args: { query: "EDDI AI platform" },
        result: '{"results": [{"title": "EDDI docs", "url": "https://docs.labs.ai"}]}',
        durationMs: 342,
        cost: 0.001,
        timestamp: new Date().toISOString(),
      },
    ]);
  }),

  // Global tool costs
  http.get("*/llm/tools/costs", () => {
    return HttpResponse.json({
      totalCost: 0.045,
      totalCalls: 15,
      perTool: {
        fetch_weather: { calls: 8, cost: 0.012 },
        websearch: { calls: 7, cost: 0.033 },
      },
    });
  }),

  // Rerun last conversation step (replay)
  http.post("*/agents/:convId/rerunLastConversationStep", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Detailed conversation (memory inspector)
  http.get("*/agents/:convId", ({ request }) => {
    const url = new URL(request.url);
    if (url.searchParams.get("returnDetailed") === "true") {
      return HttpResponse.json({
        conversationSteps: [
          {
            conversationStep: [
              { key: "actions", value: ["greet"], timestamp: new Date().toISOString(), originWorkflowId: null },
              { key: "output:text:en", value: "Hello!", timestamp: new Date().toISOString(), originWorkflowId: "wf-1" },
            ],
            timestamp: new Date().toISOString(),
          },
        ],
        conversationProperties: { environment: "test" },
      });
    }
    return HttpResponse.json({});
  }),

  // Recent logs (log viewer)
  http.get("*/logs/recent", () => {
    return HttpResponse.json([
      { level: "INFO", message: "Agent started", loggerName: "ai.labs.AgentOrchestrator", timestamp: new Date().toISOString() },
    ]);
  }),

  // Log SSE stream (log viewer) — return empty stream
  http.get("*/logs/stream", () => {
    return new HttpResponse(null, { status: 200, headers: { "Content-Type": "text/event-stream" } });
  }),

  // ─── Quotas ───────────────────────────────────────────────────────
  http.get("*/administration/quotas/:tenantId/usage", () => {
    return HttpResponse.json({
      tenantId: "default",
      conversationsToday: 42,
      apiCallsThisMinute: 8,
      monthlyCostUsd: 12.34,
      minuteWindowStart: new Date().toISOString(),
      dayStart: new Date().toISOString(),
    });
  }),

  http.get("*/administration/quotas/:tenantId", () => {
    return HttpResponse.json({
      tenantId: "default",
      maxConversationsPerDay: 1000,
      maxAgentsPerTenant: 50,
      maxApiCallsPerMinute: 120,
      maxMonthlyCostUsd: 500,
      enabled: true,
    });
  }),

  http.put("*/administration/quotas/:tenantId", async ({ request }) => {
    const body = await request.json();
    return HttpResponse.json(body);
  }),

  http.post("*/administration/quotas/:tenantId/usage/reset", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // ─── Generic resource store descriptors ──────────────────────────
  // One handler per resource store type to avoid catching agent/workflow descriptors.
  // For version lookups (includePreviousVersions), return filtered results.
  // For list requests, return sample data so the resource-list test has items.
  ...["rulestore/rulesets", "apicallstore/apicalls", "outputstore/outputsets",
      "dictionarystore/dictionaries", "parserstore/parsers", "llmstore/llms", "propertysetterstore/propertysetters",
      "mcpcallsstore/mcpcalls", "ragstore/rags"].map((storePath) => {
    const store = storePath.split("/")[0];
    const plural = storePath.split("/")[1];
    return http.get(`*/${storePath}/descriptors`, ({ request }) => {
      const url = new URL(request.url);
      const filter = url.searchParams.get("filter");
      const isVersionLookup = url.searchParams.get("includePreviousVersions") === "true";

      if (isVersionLookup && filter) {
        // Version lookup — return a single descriptor matching the filter ID
        return HttpResponse.json([
          {
            resource: `eddi://ai.labs.resource/${store}/${plural}/${filter}?version=1`,
            name: `Resource ${filter}`,
            description: "A resource",
            createdOn: Date.now() - 86400000,
            lastModifiedOn: Date.now() - 3600000,
          },
        ]);
      }

      // Normal list request
      return HttpResponse.json([
        {
          resource: `eddi://ai.labs.resource/${store}/${plural}/res1?version=1`,
          name: "Sample Resource 1",
          description: "A sample resource for testing",
          createdOn: Date.now() - 86400000,
          lastModifiedOn: Date.now() - 3600000,
        },
        {
          resource: `eddi://ai.labs.resource/${store}/${plural}/res2?version=2`,
          name: "Sample Resource 2",
          description: "Another sample resource",
          createdOn: Date.now() - 2 * 86400000,
          lastModifiedOn: Date.now() - 7200000,
        },
      ]);
    });
  }),

  // ─── Tool Metrics (Cost Dashboard / Debugger) ────────────────────
  http.get("*/llm/tools/costs/conversation/:conversationId", ({ params }) => {
    const conversationId = params.conversationId as string;
    return HttpResponse.json({
      conversationId,
      totalCost: 0.0847,
      totalToolCalls: 14,
      toolUsage: {
        "fetch_weather": { calls: 5, totalCost: 0.0125 },
        "search_products": { calls: 4, totalCost: 0.0098 },
        "create_ticket": { calls: 3, totalCost: 0.042 },
        "send_email": { calls: 2, totalCost: 0.0204 },
      },
    });
  }),

  http.get("*/llm/tools/ratelimit/:toolName", ({ params }) => {
    const toolName = params.toolName as string;
    return HttpResponse.json({
      toolName,
      limit: 60,
      remaining: 42,
      resetAt: new Date(Date.now() + 45_000).toISOString(),
    });
  }),

  http.get("*/llm/tools/cache/stats", () => {
    return HttpResponse.json({
      totalHits: 328,
      totalMisses: 97,
      hitRate: 0.772,
      perToolStats: {
        "fetch_weather": { hits: 145, misses: 32 },
        "search_products": { hits: 98, misses: 41 },
        "create_ticket": { hits: 85, misses: 24 },
      },
    });
  }),

  http.get("*/llm/tools/history/:conversationId", () => {
    const now = Date.now();
    return HttpResponse.json([
      {
        toolName: "fetch_weather",
        args: { city: "Vienna", units: "metric" },
        result: "Sunny, 22°C, humidity 45%",
        durationMs: 187,
        cost: 0.0025,
        timestamp: new Date(now - 120_000).toISOString(),
      },
      {
        toolName: "search_products",
        args: { query: "summer jackets", limit: 5 },
        result: "Found 5 matching products",
        durationMs: 342,
        cost: 0.0024,
        timestamp: new Date(now - 90_000).toISOString(),
      },
      {
        toolName: "create_ticket",
        args: { title: "Return request #4521", priority: "high" },
        result: "Ticket JIRA-4521 created",
        durationMs: 520,
        cost: 0.014,
        timestamp: new Date(now - 60_000).toISOString(),
      },
    ]);
  }),

  http.get("*/llm/tools/costs", () => {
    return HttpResponse.json({
      totalCost: 1.247,
      totalCalls: 892,
      perTool: {
        "fetch_weather": { calls: 312, cost: 0.312 },
        "search_products": { calls: 245, cost: 0.367 },
        "create_ticket": { calls: 189, cost: 0.378 },
        "send_email": { calls: 146, cost: 0.19 },
      },
    });
  }),
];

// ─── GDPR Admin Handlers ────────────────────────────────────────────────────

export const gdprHandlers = [
  // Art. 17 — Delete user data (cascade)
  http.delete("*/admin/gdpr/:userId", ({ params }) => {
    return HttpResponse.json({
      userId: params.userId as string,
      memoriesDeleted: 14,
      conversationsDeleted: 7,
      auditEntriesPseudonymized: 23,
      logEntriesPseudonymized: 89,
    });
  }),

  // Art. 15/20 — Export user data
  http.get("*/admin/gdpr/:userId/export", ({ params }) => {
    return HttpResponse.json({
      userId: params.userId as string,
      memories: [
        { key: "preferred_language", value: "en", createdAt: new Date(Date.now() - 86400000).toISOString() },
        { key: "name", value: "Jane Doe", createdAt: new Date(Date.now() - 172800000).toISOString() },
      ],
      conversations: [
        { id: "conv-1", agentId: "agent1", state: "ENDED", steps: 12, created: new Date(Date.now() - 86400000).toISOString() },
        { id: "conv-2", agentId: "agent2", state: "IN_PROGRESS", steps: 3, created: new Date(Date.now() - 3600000).toISOString() },
      ],
      managedConversations: [],
    });
  }),

  // Art. 18 — Restrict processing
  http.post("*/admin/gdpr/:userId/restrict", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Art. 18 — Unrestrict processing
  http.delete("*/admin/gdpr/:userId/restrict", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Art. 18 — Check restriction status
  http.get("*/admin/gdpr/:userId/restrict", () => {
    return HttpResponse.json(false);
  }),
];

// ─── User Memory Handlers ───────────────────────────────────────────────────

const MOCK_MEMORIES = [
  {
    id: "mem-1",
    userId: "user-123",
    key: "preferred_language",
    value: "en",
    category: "preference",
    visibility: "self",
    sourceAgentId: "agent1",
    conflicted: false,
    accessCount: 12,
    createdAt: new Date(Date.now() - 7 * 86400000).toISOString(),
    updatedAt: new Date(Date.now() - 3600000).toISOString(),
  },
  {
    id: "mem-2",
    userId: "user-123",
    key: "favorite_color",
    value: "blue",
    category: "preference",
    visibility: "self",
    sourceAgentId: "agent1",
    conflicted: false,
    accessCount: 3,
    createdAt: new Date(Date.now() - 5 * 86400000).toISOString(),
    updatedAt: new Date(Date.now() - 86400000).toISOString(),
  },
  {
    id: "mem-3",
    userId: "user-123",
    key: "account_type",
    value: "premium",
    category: "fact",
    visibility: "global",
    sourceAgentId: "agent2",
    conflicted: false,
    accessCount: 28,
    createdAt: new Date(Date.now() - 14 * 86400000).toISOString(),
    updatedAt: new Date(Date.now() - 7200000).toISOString(),
  },
  {
    id: "mem-4",
    userId: "user-123",
    key: "last_topic",
    value: "billing",
    category: "context",
    visibility: "self",
    sourceAgentId: "agent1",
    sourceConversationId: "conv-42",
    conflicted: true,
    accessCount: 1,
    createdAt: new Date(Date.now() - 3600000).toISOString(),
    updatedAt: new Date(Date.now() - 1800000).toISOString(),
  },
];

export const userMemoryHandlers = [
  http.get("*/usermemorystore/memories/:userId", () => {
    return HttpResponse.json(MOCK_MEMORIES);
  }),

  http.get("*/usermemorystore/memories/:userId/search", ({ request }) => {
    const url = new URL(request.url);
    const q = (url.searchParams.get("q") ?? "").toLowerCase();
    const filtered = MOCK_MEMORIES.filter(
      (m) => m.key.toLowerCase().includes(q) || String(m.value).toLowerCase().includes(q),
    );
    return HttpResponse.json(filtered);
  }),

  http.get("*/usermemorystore/memories/:userId/category/:category", ({ params }) => {
    const filtered = MOCK_MEMORIES.filter((m) => m.category === params.category);
    return HttpResponse.json(filtered);
  }),

  http.get("*/usermemorystore/memories/:userId/count", () => {
    return HttpResponse.json({ count: MOCK_MEMORIES.length });
  }),

  http.put("*/usermemorystore/memories", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.delete("*/usermemorystore/memories/entry/:entryId", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  http.delete("*/usermemorystore/memories/:userId", () => {
    return new HttpResponse(null, { status: 204 });
  }),
];

// ─── Properties Handlers ────────────────────────────────────────────────────

const MOCK_PROPERTIES = {
  user_name: { name: "user_name", scope: "longTerm", valueString: "Jane Doe" },
  age: { name: "age", scope: "longTerm", valueInt: 32 },
  is_vip: { name: "is_vip", scope: "longTerm", valueBoolean: true },
  preferences: { name: "preferences", scope: "longTerm", valueObject: { theme: "dark", lang: "en" } },
  tags: { name: "tags", scope: "longTerm", valueList: ["loyal", "premium"] },
};

export const propertiesHandlers = [
  http.get("*/propertiesstore/properties/:userId", () => {
    return HttpResponse.json(MOCK_PROPERTIES);
  }),

  http.post("*/propertiesstore/properties/:userId", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.delete("*/propertiesstore/properties/:userId", () => {
    return new HttpResponse(null, { status: 204 });
  }),
];

// ─── Agent Trigger Handlers ─────────────────────────────────────────────────

const MOCK_TRIGGERS = [
  {
    intent: "booking_request",
    agentDeployments: [
      { environment: "production", agentId: "agent1" },
      { environment: "test", agentId: "agent3" },
    ],
  },
  {
    intent: "faq_query",
    agentDeployments: [
      { environment: "production", agentId: "agent2" },
    ],
  },
  {
    intent: "escalation",
    agentDeployments: [
      { environment: "production", agentId: "agent1" },
      { environment: "production", agentId: "agent8" },
    ],
  },
];

export const triggerHandlers = [
  http.get("*/AgentTriggerStore/agenttriggers", () => {
    return HttpResponse.json(MOCK_TRIGGERS);
  }),

  http.get("*/AgentTriggerStore/agenttriggers/:intent", ({ params }) => {
    const found = MOCK_TRIGGERS.find((t) => t.intent === params.intent);
    if (!found) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(found);
  }),

  http.post("*/AgentTriggerStore/agenttriggers", () => {
    return new HttpResponse(null, { status: 201 });
  }),

  http.put("*/AgentTriggerStore/agenttriggers/:intent", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.delete("*/AgentTriggerStore/agenttriggers/:intent", () => {
    return new HttpResponse(null, { status: 204 });
  }),
];

// ─── Capability Registry Handlers ────────────────────────────────────────────

const MOCK_CAPABILITIES = [
  { agentId: "agent1", skill: "customer-support", attributes: { language: "en" }, confidence: "high" },
  { agentId: "agent1", skill: "order-tracking", attributes: {}, confidence: "high" },
  { agentId: "agent2", skill: "faq", attributes: { domain: "product" }, confidence: "medium" },
  { agentId: "agent3", skill: "code-review", attributes: { language: "java" }, confidence: "high" },
];

const ALL_SKILLS = ["customer-support", "order-tracking", "faq", "code-review", "data-validation", "invoice-parsing"];

export const capabilityHandlers = [
  // Skills list — must come BEFORE the query-param search handler
  http.get("*/capabilities/skills", () => {
    return HttpResponse.json(ALL_SKILLS);
  }),

  // Skill search with query params (?skill=...&strategy=...)
  http.get("*/capabilities", ({ request }) => {
    const url = new URL(request.url);
    const skill = url.searchParams.get("skill") ?? "";
    if (!skill) return HttpResponse.json(MOCK_CAPABILITIES);
    const filtered = MOCK_CAPABILITIES.filter((c) =>
      c.skill.toLowerCase().includes(skill.toLowerCase()),
    );
    return HttpResponse.json(filtered);
  }),
];

// ─── Backup / Sync Handlers ──────────────────────────────────────────────────

const MOCK_EXPORT_PREVIEW = {
  agentId: "agent1",
  agentName: "Support Agent",
  agentVersion: 3,
  resources: [
    { resourceId: "agent1", resourceVersion: 3, resourceType: "agent", name: "Support Agent", parentWorkflowId: null, workflowIndex: 0, required: true },
    { resourceId: "pkg1", resourceVersion: 2, resourceType: "workflow", name: "Support Ticket Pipeline", parentWorkflowId: null, workflowIndex: 0, required: true },
    { resourceId: "beh1", resourceVersion: 1, resourceType: "behavior", name: "Support Rules", parentWorkflowId: "pkg1", workflowIndex: 0, required: false },
    { resourceId: "llm1", resourceVersion: 1, resourceType: "langchain", name: "GPT-4 Task", parentWorkflowId: "pkg1", workflowIndex: 1, required: false },
    { resourceId: "out1", resourceVersion: 1, resourceType: "output", name: "Support Outputs", parentWorkflowId: "pkg1", workflowIndex: 2, required: false },
    { resourceId: "ps1", resourceVersion: 1, resourceType: "property", name: "Props", parentWorkflowId: "pkg1", workflowIndex: 3, required: false },
    { resourceId: "dict1", resourceVersion: 1, resourceType: "regulardictionary", name: "Support Dict", parentWorkflowId: "pkg1", workflowIndex: 4, required: false },
    { resourceId: "snip1", resourceVersion: 1, resourceType: "snippet", name: "System Prompt Base", parentWorkflowId: null, workflowIndex: 0, required: false },
  ],
};

const MOCK_IMPORT_PREVIEW = {
  sourceAgentId: "agent1",
  sourceAgentName: "Support Agent",
  targetAgentId: "agent1",
  targetAgentName: "Support Agent",
  resources: [
    { sourceId: "beh1", resourceType: "behavior", name: "Support Rules", action: "UPDATE", targetId: "beh1-local", targetVersion: 1, matchStrategy: "type", sourceContent: '{"behaviorGroups":[{"name":"main","rules":[{"name":"greet","actions":["greet"]}]}]}', targetContent: '{"behaviorGroups":[{"name":"main","rules":[{"name":"greet","actions":["hello"]}]}]}', workflowIndex: 0 },
    { sourceId: "llm1", resourceType: "langchain", name: "GPT-4 Task", action: "SKIP", targetId: "llm1-local", targetVersion: 1, matchStrategy: "type", sourceContent: null, targetContent: null, workflowIndex: 1 },
    { sourceId: "out1", resourceType: "output", name: "Support Outputs", action: "UPDATE", targetId: "out1-local", targetVersion: 1, matchStrategy: "name", sourceContent: '{"outputSet":[{"action":"greet","outputs":[{"valueAlternatives":[{"type":"text","text":"Hello!"}]}]}]}', targetContent: '{"outputSet":[{"action":"greet","outputs":[{"valueAlternatives":[{"type":"text","text":"Hi!"}]}]}]}', workflowIndex: 2 },
    { sourceId: "snip-new", resourceType: "snippet", name: "New Snippet", action: "CREATE", targetId: null, targetVersion: null, matchStrategy: null, sourceContent: '{"content":"You are a helpful assistant."}', targetContent: null, workflowIndex: 0 },
  ],
};

const MOCK_REMOTE_AGENTS = [
  { resource: "eddi://ai.labs.agent/agentstore/agents/remote-agent1?version=5", name: "Support Agent", description: "Support bot", lastModifiedOn: new Date().toISOString() },
  { resource: "eddi://ai.labs.agent/agentstore/agents/remote-agent2?version=3", name: "FAQ Agent", description: "FAQ bot", lastModifiedOn: new Date().toISOString() },
  { resource: "eddi://ai.labs.agent/agentstore/agents/remote-agent3?version=1", name: "Sales Agent", description: "Sales bot", lastModifiedOn: new Date().toISOString() },
  { resource: "eddi://ai.labs.agent/agentstore/agents/remote-agent4?version=2", name: "Onboarding Agent", description: "New hire guide", lastModifiedOn: new Date().toISOString() },
];

export const backupSyncHandlers = [
  // Export preview
  http.post("*/backup/export/:agentId/preview", () => {
    return HttpResponse.json(MOCK_EXPORT_PREVIEW);
  }),

  // Selective export — returns Location header
  http.post("*/backup/export/:agentId", () => {
    return new HttpResponse(null, {
      status: 200,
      headers: { Location: "/backup/export/agent-export.zip" },
    });
  }),

  // Export file download
  http.get("*/backup/export/:filename", () => {
    return new HttpResponse(new Blob(["fake-zip-content"]), {
      status: 200,
      headers: { "Content-Type": "application/zip" },
    });
  }),

  // Import preview (merge or upgrade)
  http.post("*/backup/import/preview", ({ request }) => {
    const url = new URL(request.url);
    const targetAgentId = url.searchParams.get("targetAgentId");
    if (targetAgentId) {
      // Upgrade preview
      return HttpResponse.json(MOCK_IMPORT_PREVIEW);
    }
    // Merge preview
    return HttpResponse.json({
      ...MOCK_IMPORT_PREVIEW,
      targetAgentId: null,
      targetAgentName: null,
    });
  }),

  // Import execute (create, merge, or upgrade)
  http.post("*/backup/import", () => {
    const newId = `imported-${Date.now()}`;
    return new HttpResponse(null, {
      status: 202,
      headers: { Location: `/agentstore/agents/${newId}?version=1` },
    });
  }),

  // List remote agents
  http.get("*/backup/import/sync/agents", () => {
    return HttpResponse.json(MOCK_REMOTE_AGENTS);
  }),

  // Sync preview (single)
  http.post("*/backup/import/sync/preview", () => {
    return HttpResponse.json(MOCK_IMPORT_PREVIEW);
  }),

  // Sync preview (batch)
  http.post("*/backup/import/sync/preview/batch", async ({ request }) => {
    const mappings = (await request.json()) as Array<{ sourceAgentId: string }>;
    return HttpResponse.json(
      mappings.map((m) => ({
        ...MOCK_IMPORT_PREVIEW,
        sourceAgentId: m.sourceAgentId,
      }))
    );
  }),

  // Sync execute (single)
  http.post("*/backup/import/sync", () => {
    return new HttpResponse(null, { status: 202 });
  }),

  // Sync execute (batch)
  http.post("*/backup/import/sync/batch", () => {
    return new HttpResponse(null, { status: 202 });
  }),

  // ── User Conversation Store ──

  http.get("*/userconversationstore/userconversations/:intent/:userId", ({ params }) => {
    return HttpResponse.json({
      intent: params.intent,
      userId: params.userId,
      environment: "production",
      agentId: "agent1",
      conversationId: "conv1",
    });
  }),

  http.post("*/userconversationstore/userconversations/:intent/:userId", () => {
    return new HttpResponse(null, {
      status: 201,
      headers: { Location: "/userconversationstore/userconversations/test/user1" },
    });
  }),

  http.delete("*/userconversationstore/userconversations/:intent/:userId", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // ── Conversation Attachments ──

  http.post("*/conversations/:conversationId/attachments", () => {
    return HttpResponse.json({
      storageRef: `attachment-${Date.now()}`,
      fileName: "document.pdf",
      mimeType: "application/pdf",
      sizeBytes: 102400,
    });
  }),

  // ── Conversation Rerun ──

  http.post("*/agents/:conversationId/rerun", () => {
    return new HttpResponse(null, { status: 200 });
  }),
];
