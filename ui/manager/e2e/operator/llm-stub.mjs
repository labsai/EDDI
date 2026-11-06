// A deterministic OpenAI-compatible chat-completions server for the operator
// activation E2E tier (see operator-activation.operator.spec.ts).
//
// The tier provisions the Platform Operator against a real EDDI with the
// `openai-compatible` provider pointed here, so the whole activation path —
// setup-api, deploy, gate read-back, gate dry-run, read canary, write canary —
// runs exactly as it does for an administrator, with no paid model and no
// network. The "model" is a few rules over the last message:
//
//   * a tool result came back      → answer in text, quoting it, and say whether
//                                     it looked like a failure (the spec asserts
//                                     on the OPERATOR_E2E_TOOL_OK marker)
//   * the write canary's prompt     → call exactly the tool it names, on the id
//                                     it names, with a JSON Patch body
//   * a request to list the agents  → call the agent-descriptor listing tool
//   * anything else                 → a short plain answer
//
// Every request is also logged (tool count and the decision) so a failure in CI
// can be read from the compose logs. No dependencies: plain node:http.

import http from "node:http";

const PORT = Number(process.env.PORT) || 8089;
const MODEL = "operator-e2e-stub";

let sequence = 0;

/** The text of an OpenAI message whose content may be a string or parts. */
function textOf(message) {
  if (!message) return "";
  const { content } = message;
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content.map((part) => (typeof part === "string" ? part : (part?.text ?? ""))).join("");
  }
  return "";
}

function toolName(tool) {
  return tool?.function?.name ?? tool?.name ?? "";
}

function toolParams(tool) {
  return tool?.function?.parameters ?? { type: "object", properties: {} };
}

/**
 * The tool that lists agents. Matched on the generated name, which is the
 * operationId when the spec has one (EDDI's do) or a method_path slug.
 */
function findListAgentsTool(tools) {
  const names = tools.map(toolName);
  const preferred = [
    (n) => /^readAgentDescriptors$/i.test(n),
    (n) => /agent/i.test(n) && /descriptor/i.test(n) && /read|list|get/i.test(n),
    (n) => /^get_agentstore_agents_descriptors$/i.test(n),
  ];
  for (const test of preferred) {
    const hit = tools.find((t) => test(toolName(t)));
    if (hit) {
      if (requiredOf(hit).length > 0) {
        console.log(`[llm-stub] ${toolName(hit)} has required parameters: ${JSON.stringify(toolParams(hit))}`);
      }
      return hit;
    }
  }
  console.log(`[llm-stub] no agent-listing tool among ${names.length}: ${names.slice(0, 40).join(", ")}`);
  return null;
}

function requiredOf(tool) {
  const params = toolParams(tool);
  return Array.isArray(params.required) ? params.required : [];
}

/** Arguments for the write canary's call, filled from the tool's own schema. */
function writeCanaryArguments(tool, id) {
  const params = toolParams(tool);
  const properties = params.properties ?? {};
  const args = {};
  // EDDI's descriptor PATCH body is {operation, document}, not RFC 6902.
  const document = { name: "Platform Operator [operator-write-canary]" };
  const as = (value, type) => (type === "string" && typeof value !== "string" ? JSON.stringify(value) : value);
  for (const name of Object.keys(properties)) {
    const type = properties[name]?.type;
    if (/^id$|Id$/.test(name)) args[name] = id;
    else if (/^version$/i.test(name)) args[name] = type === "integer" || type === "number" ? 1 : "1";
    else if (/^operation$/i.test(name)) args[name] = "SET";
    else if (/^document$/i.test(name)) args[name] = as(document, type);
    else if (/body/i.test(name)) args[name] = as({ operation: "SET", document }, type);
  }
  console.log(`[llm-stub] write canary schema ${JSON.stringify(params)} → ${JSON.stringify(args)}`);
  return args;
}

/** Decide the next assistant message: `{ text }` or `{ toolCall: { name, arguments } }`. */
function decide(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const tools = Array.isArray(body.tools) ? body.tools : [];
  const last = messages[messages.length - 1];

  if (last?.role === "tool") {
    const result = textOf(last);
    // A transport or HTTP failure (`{"error": …}`, a 4xx/5xx), or a call the
    // engine refused to run (a schema violation answers NOT_EXECUTED).
    const failed =
      /^\s*\{\s*"error"\s*:/.test(result) ||
      /"httpCode"\s*:\s*[45]\d\d/.test(result) ||
      /"status"\s*:\s*"NOT_EXECUTED"/.test(result);
    const marker = failed ? "OPERATOR_E2E_TOOL_FAILED" : "OPERATOR_E2E_TOOL_OK";
    return { text: `${marker} ${result.slice(0, 300)}`, why: `tool result (${failed ? "failure" : "ok"})` };
  }

  const prompt = textOf([...messages].reverse().find((m) => m.role === "user"));

  const canary = /Call `([^`]+)` with id `([^`]+)`/.exec(prompt);
  if (canary) {
    const [, name, id] = canary;
    const tool = tools.find((t) => toolName(t) === name);
    if (!tool) return { text: `The tool ${name} is not available to me.`, why: `write canary: no tool ${name}` };
    return { toolCall: { name, arguments: writeCanaryArguments(tool, id) }, why: `write canary → ${name}` };
  }

  if (/list the agents/i.test(prompt)) {
    const tool = findListAgentsTool(tools);
    if (!tool) return { text: "I have no tool to list agents.", why: "list agents: no tool" };
    // The generated tool marks every query parameter required, defaults
    // included, so each gets a value of its declared type.
    const properties = toolParams(tool).properties ?? {};
    const args = Object.fromEntries(
      requiredOf(tool).map((name) => {
        const type = properties[name]?.type;
        if (type === "integer" || type === "number") return [name, /limit/i.test(name) ? 10 : 0];
        if (type === "boolean") return [name, false];
        return [name, ""];
      }),
    );
    return { toolCall: { name: toolName(tool), arguments: args }, why: `list agents → ${toolName(tool)}` };
  }

  return { text: "Hello from the operator E2E stub model.", why: "plain" };
}

function completion(decision) {
  const id = `chatcmpl-stub-${++sequence}`;
  const created = Math.floor(Date.now() / 1000);
  const message = decision.toolCall
    ? {
        role: "assistant",
        content: null,
        tool_calls: [
          {
            id: `call_${sequence}`,
            type: "function",
            function: { name: decision.toolCall.name, arguments: JSON.stringify(decision.toolCall.arguments) },
          },
        ],
      }
    : { role: "assistant", content: decision.text };
  return {
    id,
    object: "chat.completion",
    created,
    model: MODEL,
    choices: [{ index: 0, message, finish_reason: decision.toolCall ? "tool_calls" : "stop" }],
    usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
  };
}

function streamChunks(decision) {
  const id = `chatcmpl-stub-${++sequence}`;
  const created = Math.floor(Date.now() / 1000);
  const chunk = (delta, finish = null, extra = {}) => ({
    id,
    object: "chat.completion.chunk",
    created,
    model: MODEL,
    choices: [{ index: 0, delta, finish_reason: finish }],
    ...extra,
  });
  if (decision.toolCall) {
    return [
      chunk({
        role: "assistant",
        content: null,
        tool_calls: [
          {
            index: 0,
            id: `call_${sequence}`,
            type: "function",
            function: { name: decision.toolCall.name, arguments: JSON.stringify(decision.toolCall.arguments) },
          },
        ],
      }),
      chunk({}, "tool_calls"),
      { id, object: "chat.completion.chunk", created, model: MODEL, choices: [], usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } },
    ];
  }
  return [
    chunk({ role: "assistant", content: decision.text }),
    chunk({}, "stop"),
    { id, object: "chat.completion.chunk", created, model: MODEL, choices: [], usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } },
  ];
}

function send(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(payload));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://stub");
  if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { status: "UP" });
  if (req.method === "GET" && /\/models$/.test(url.pathname)) {
    return send(res, 200, { object: "list", data: [{ id: MODEL, object: "model", owned_by: "e2e" }] });
  }
  if (req.method !== "POST" || !/\/chat\/completions$/.test(url.pathname)) {
    console.log(`[llm-stub] 404 ${req.method} ${url.pathname}`);
    return send(res, 404, { error: { message: `no route ${req.method} ${url.pathname}` } });
  }
  let raw = "";
  req.setEncoding("utf8");
  req.on("data", (part) => (raw += part));
  req.on("end", () => {
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      return send(res, 400, { error: { message: "body is not JSON" } });
    }
    const decision = decide(body);
    const tools = Array.isArray(body.tools) ? body.tools.length : 0;
    console.log(`[llm-stub] #${sequence + 1} stream=${!!body.stream} tools=${tools} messages=${body.messages?.length ?? 0} → ${decision.why}`);
    if (body.stream) {
      res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache", connection: "keep-alive" });
      for (const c of streamChunks(decision)) res.write(`data: ${JSON.stringify(c)}\n\n`);
      res.end("data: [DONE]\n\n");
      return;
    }
    send(res, 200, completion(decision));
  });
});

server.listen(PORT, "0.0.0.0", () => console.log(`[llm-stub] listening on :${PORT}`));
