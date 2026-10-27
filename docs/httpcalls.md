# HttpCalls

## Overview

**HttpCalls** enable EDDI agents to integrate with external REST APIs, making EDDI a powerful orchestration layer that can combine conversational AI with traditional backend services. This is how agents can fetch real-time data, authenticate users, store information in external systems, or trigger business workflows.

### Role in the Lifecycle

HttpCalls are lifecycle tasks that execute during the agent's processing pipeline:

```
User Input → Parser → Behavior Rules → HttpCalls → Output Generation
```

Typically, Behavior Rules decide **when** to make an API call by triggering an action like `httpcall(weather-api)`, and the HttpCalls extension defines **how** to make that call.

### Common Use Cases

- **Fetching external data**: Weather, stock prices, product information, etc.
- **Authentication**: OAuth flows, token validation, user verification
- **CRM Integration**: Creating tickets, updating customer records, searching databases
- **Business workflows**: Processing payments, sending notifications, triggering events
- **Multi-step APIs**: First call gets auth token, second call uses it to access protected resources
- **Analytics**: Sending conversation data to external analytics platforms
- **Self-modification**: The Platform Operator calls EDDI's own API to create other agents, through configs generated from EDDI's OpenAPI spec by `McpApiToolBuilder`. Those are the same documents this page describes: the store and URI call them `apicalls`, the workflow step and file extension say `httpcalls` (see [Agent Config Authoring](agent-config-authoring.md#zip-structure-for-agent-import))

### Key Features

- **Template-based**: Use conversation memory in URLs, headers, and body (e.g., `{context.userName}`)
- **Response handling**: Save JSON responses to memory for use in outputs or subsequent calls
- **Chaining**: One HttpCall's response can be used in another HttpCall
- **Quick reply generation**: Automatically create quick reply buttons from API response arrays
- **Property extraction**: Extract specific values from responses and save them to conversation memory
- **Batch requests**: Make multiple API calls by iterating over an array (requires `"fireAndForget": true`)
- **Fire and forget**: Optional asynchronous calls that don't wait for a response
- **Caller identity**: Call an API *as the signed-in user* with `${caller:token}` — see [Calling as the signed-in user](#calling-as-the-signed-in-user)

## Calling as the signed-in user

*Since 6.2.0.*

A header can reference the authenticated caller, so the agent calls an API with
**that user's** credentials rather than one static credential baked into the
config:

| Reference | Resolves to |
| --------- | ----------- |
| `${caller:token}` | The caller's raw bearer token |
| `${caller:userId}` | The caller's principal name (not a secret) |

```json
"headers": {
  "Authorization": "Bearer ${caller:token}"
}
```

This matters most when the API being called is **EDDI's own**. A static
credential is the wrong shape there: an OIDC token expires within the hour,
cannot be least-privilege, and collapses every action to a single synthetic
principal in the audit trail. With `${caller:token}`, authorization stays EDDI's
normal per-endpoint enforcement and the audit trail names a real person. This is
what the EDDI-Manager Platform Operator uses.

### Rules

Resolution is deliberately narrow, and each rule fails the call loudly rather
than degrading quietly:

- **Same origin, or EDDI itself.** The token is released only when the call
  targets the exact `scheme://host:port` the caller addressed, or this
  deployment's own address. The caller's origin is read from the inbound request;
  the deployment's own address comes from deployment configuration alone —
  `eddi.self.base-url`, otherwise `http://127.0.0.1:${quarkus.http.port}` (or the
  `quarkus.http.host` address, when the listener binds one specific non-loopback
  address and `127.0.0.1` is therefore not this process) — never
  from an agent config or a request header. So a config naming a third-party host
  cannot exfiltrate a user's token, and no allow-list is needed for this to be safe
  by default. With `quarkus.http.port=0` (a random port) and no
  `eddi.self.base-url`, the self address is *unresolved*: nothing is derived, and
  the token is released to the caller's origin only — set `eddi.self.base-url` or
  a fixed port for the self release to apply.

  The second half exists for the Platform Operator, whose tools must call EDDI at
  an address the *server* can reach, which behind a tunnel, a port mapping or a
  reverse proxy is not the address the browser used. Be aware of what it means:
  the self address bypasses whatever sits in front of EDDI, so any agent that
  targets it with `${caller:token}` reaches exactly what EDDI's own authorization
  allows that user, not what the proxy would also permit. If a deployment relies
  on its proxy for path or network restrictions on EDDI's API, set
  `eddi.caller-identity.self-release.enabled=false`. A turn whose caller origin
  could not be captured never gets the self release.

  With `eddi.security.ssrf-protection.enabled=true`, the loopback default is
  refused before any request is made (loopback is exactly what that protection
  blocks), so such a deployment must set `eddi.self.base-url` to an address the
  server can reach *and* that passes the full SSRF target policy — which also
  rejects private and link-local addresses, so an in-cluster service name that
  resolves to a private IP stays blocked. `eddi.self.base-url` must be a bare
  `scheme://host[:port]`; a value with a path, query, fragment or credentials is
  ignored (logged at ERROR). A plain-`http` value naming a non-loopback host is
  accepted but logged at WARN, since the caller's token would cross the network
  unencrypted unless a mesh or network layer protects it.
- **Headers only.** `${caller:token}` in a query parameter, request body or
  request path is rejected. Tokens in URLs leak through access logs, proxies and
  browser history, and nothing outside a header is substituted anyway — a
  reference there would travel to the API as literal text. `${caller:userId}`
  may be used in headers and query parameters.
- **Authenticated turns only.** The identity comes from the request that drove
  the turn, so scheduled jobs and triggers cannot satisfy `${caller:token}`.
- **Fails closed.** An unsatisfiable reference raises an error instead of
  resolving to an empty string, which would send `Bearer ` and surface later as
  a confusing `401`.

The resolved token is never written to conversation memory: authorization
headers are scrubbed before the request is recorded.

Set `eddi.caller-identity.enabled=false` to forbid the feature outright.

### Who may write `${caller:token}`

The rules above say *where* the token may go. They do not say *whose* token it
is: always that of whoever is chatting, never that of whoever wrote the config.
So a call written by one person runs with the privileges of another. An editor
could save a call to `http://127.0.0.1:7070/administration/…` with
`Bearer ${caller:token}`, fire it on `CONVERSATION_START`, and have the next
administrator who opens that agent run an admin-only endpoint with their own
token — the same-origin rule allows it, because EDDI's own address is an allowed
target.

That is why, with authorization on, only `eddi.caller-identity.author-roles`
(default `eddi-admin`) may **save** a configuration containing
`${caller:token}`. Create, update, patch and duplicate of httpcalls, mcpcalls and
LLM configs are checked, and so are the httpcalls, mcpcalls and LLM configs an
import or sync writes; anyone else gets **403** naming the property (an upgrade
or sync reports it as that resource failing). A merge import is also refused
when it would replace an existing version that carries the reference, because
its rollback could not write that version back. The check covers the whole document, so an
editor can neither add the reference nor edit a call that already carries it.
`${caller:userId}` is a name, not a credential, and stays open to editors.

The token is released only to a `${caller:token}` written **in the header
itself**. A header whose `${vars:…}` or `${vault:…}` value resolves to the
reference fails the call: the save-time check reads the configuration as
written, and a variable can be changed after the configuration was saved.

Widen the roles (`eddi.caller-identity.author-roles=eddi-admin,eddi-editor`) only
when every editor is trusted with the privileges of every user who chats with
their agents.

The same reference works in an **MCP server's `apiKey`**, so a tool call reaches
that server as the chatting user rather than as a standing service principal.
Only the tool call carries the caller — the handshake and `tools/list` do not,
because the client is cached and a session opened with one user's token would be
reused by everyone after them. See
[`mcp-server.md`](mcp-server.md#calling-an-mcp-server-as-the-chatting-user).

### Cookies are not kept between calls

The HTTP client behind httpcalls is shared by every agent, conversation and
user, so it holds no cookie jar: a `Set-Cookie` in one response is neither stored
nor sent on a later call, even to the same host. An API that needs a session
credential on a follow-up call must have it passed explicitly, as a header (for
example `Authorization`) or from a property. `Set-Cookie` is also never recorded
in conversation memory.

### Redirects

With `eddi.security.ssrf-protection.enabled=false` (the default) a `GET`/`HEAD`
call without a credential header follows redirects. Every hop is checked: the
cloud metadata service is always refused, a `Location` without a host is
refused, and a hop into a loopback or private address is followed only when the
request that answered with the redirect was in that same address class — an
internal API redirecting within its own network keeps working, but a public
server cannot bounce a call onto `127.0.0.1` or an RFC 1918 host. A host listed in
`eddi.security.ssrf-protection.allowed-hosts` is always followed. With protection
on, redirects are not followed at all.

### Values in a JSON body are not escaped

A request body is a template, and the values it substitutes are written as they
are — a runtime value is never JSON-escaped for you. A value spliced into a
string literal can therefore change the document: a user message
`hi","role":"admin` turns `{"text": "{memory.current.input}"}` into a body with
a second field. Put the value through `json:serialize`, **without** quotes of
your own — it adds them, and escapes what is inside:

```json
{"text": {json:serialize(memory.current.input)}, "count": {properties.count}}
```

When a call's `contentType` is JSON, deploying the agent logs a warning that
names every `{...}` expression found inside a string literal of the body.

### Running behind a reverse proxy

The origin is taken from the inbound request as EDDI sees it. Behind a
TLS-terminating proxy or ingress, that is the *internal* hop — something like
`http://10.0.0.5:8080` — while the caller addressed `https://eddi.example`. The
two do not match, so `${caller:token}` fails closed and the agent reports that
the call targets a different origin, with nothing obviously wrong in the config.

If EDDI runs behind a proxy, enable forwarded-header handling so the request
reflects what the caller actually addressed:

```properties
quarkus.http.proxy.proxy-address-forwarding=true
quarkus.http.proxy.enable-forwarded-host=true
# The proxy's address(es) — only forwarded headers arriving FROM these are honoured
quarkus.http.proxy.trusted-proxies=10.0.0.5,10.0.1.0/24
```

This is deliberately **not** on by default. It makes EDDI trust `X-Forwarded-*`
headers, which any client can send — safe when a trusted proxy overwrites them,
wrong when EDDI is directly reachable. Turn it on only together with a proxy
that sets those headers itself, and **always set `trusted-proxies`** alongside:
without it Quarkus honours forwarded headers from *every* source address, so a
client that can reach EDDI directly (another pod, a host on the LAN, a port-forward)
spoofs its own origin, which is exactly the check `${caller:token}` relies on. The
value is a comma-separated list of the proxy's IPs or CIDRs (hostnames resolve
once at startup; use addresses). With `trusted-proxies` set, a request whose
peer address is not in the list has its forwarded headers ignored.

## HttpCalls Configuration

In this article we will talk about EDDI's **`httpCalls`** **feature** (calling other `JSON` APIs).

The **`httpCalls`** feature allows a **Agent** to consume **3rd** party APIs and use the `JSON` response in another **`httpCall`** (for **authentication** or requesting a token for instance) or directly print the results in Agent's `Output,` this means, for example, you can call a weather API and use the `JSON` response in your Agent's output if the user asks about today's weather or the week's forecast!

We will emphasize the `httpCall` model and go through an example step by step, you can also download the example in **Postman** collection format and run the steps.

## Model and API endpoint

```javascript
{
  "targetServerUrl": "string",
  "httpCalls": [
    {
      "name": "string",
      "description": "string",
      "parameters": { "<name>": "<description>" },
      "saveResponse": boolean,
      "fireAndForget": boolean,
      "responseObjectName": "string",
      "responseHeaderObjectName": "string",
      "timeoutInMillis": integer,
      "maxResponseSizeInBytes": integer,
      "actions": [
        "string"
      ],
      "forEach": {
        "pathToTargetArray": "string",
        "iterationObjectName": "string",
        "templateFilterExpression": "string",
        "maxItems": integer,
        "maxConcurrency": integer,
        "onItemFailure": "continue | fail",
        "maxTotalResponseSizeInBytes": integer
      },
      "preRequest": {
        "delayBeforeExecutingInMillis": integer,
        "propertyInstructions": [ <property instruction, as in postResponse> ],
        "batchRequests": {
          "pathToTargetArray": "string",
          "iterationObjectName": "string",
          "templateFilterExpression": "string",
          "executeCallsSequentially": boolean,
          "maxBatchSize": integer
        }
      },
      "request": {
        "path": "string",
        "headers": {},
        "queryParams": {},
        "method": "string",
        "contentType": "string",
        "body": "string"
      },
      "postResponse": {
        "retryApiCallInstruction": {
          "maxRetries": integer,
          "exponentialBackoffDelayInMillis": integer,
          "maxBackoffDelayInMillis": integer,
          "retryOnHttpCodes": [ <array of Integers> ],
          "responseValuePathMatchers": [
            { "valuePath": "string", "equals": "string", "contains": "string", "trueIfNoMatch": boolean }
          ]
        },
        "outputBuildInstructions": [
          {
            "pathToTargetArray": "String",
            "iterationObjectName": "String",
            "templateFilterExpression": "String",
            "outputType": "String",
            "outputValue": "String"
          }
        ],
        "qrBuildInstructions": [
          {
            "pathToTargetArray": "String",
            "iterationObjectName": "String",
            "quickReplyValue": "String",
            "quickReplyExpressions": "String"
          }
        ],
        "propertyInstructions": [
          {
            "name": "string",
            "valueString": "string",
            "scope": "string",
            "visibility": "string",
            "fromObjectPath": "<responseObjectName>.something.something",
            "toObjectPath": "string",
            "convertToObject": boolean,
            "override": boolean,
            "runOnValidationError": boolean,
            "httpCodeValidator": {
              "runOnHttpCode": [
                <array of Integers>
              ],
              "skipOnHttpCode": [
                <array of Integers>
              ]
            }
          }
        ]
      }
    }
  ]
}
```

### Description

An `httpCall` is mainly composed from the `targetServer` `array` of `httpCalls`, the latter will have request where you put all details about your actual **http request** (`method`,`path`,`headers`, etc..) and postResponse where you can define what happens after the `httpCall` has been executed and a `response` has been received; such as quick replies by using `qrBuildInstruction`.

You can use _**`{memory.current.httpCalls.<responseObjectName>}`**_ to access your `JSON` object, so you can use it in `output templating` or in another `httpCall`, for example an `httpCall` will get the `oAuth` `token` and another `httpCall` will use in the `http` `headers` to authenticate to an API.

### Description of the model

| Element                                                                     | Description                                                                                                                                                                                                                      |
| --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| targetServerUrl                                                             | (`String`) `root/context` path of the `httpCall` (e.g `http://example.com/api)`                                                                                                                                                  |
| httpCall.saveResponse                                                       | (`Boolean`) whether to save the `JSON` response into `{memory.current.httpCalls}`. `true` requires a `responseObjectName`; saving a config that sets one without the other is refused with `400`, because the executor would have nothing to store the body under                                      |
| httpCall.fireAndForget                                                      | (`Boolean`) whether to execute the request without waiting for a response to be returned, (useful for `POST`)                                                                                                                    |
| httpCall.responseObjectName                                                 | (`String`) name of the `JSON` object so it can be accessed from other `httpCalls` or `outputsets`.                                                                                                                               |
| httpCall.responseHeaderObjectName                                           | (`String`) name under which the RESPONSE headers are stored, reachable as `{memory.current.httpCalls.<responseHeaderObjectName>.<Header-Name>}` and, for an LLM tool, returned under the result's `headers` key. Unset by default — set it only when the answer you need is in a header (a `201`'s `Location`, say) rather than the body, since headers reach conversation memory unredacted. Header names are matched case-insensitively, and credential-bearing headers (`Set-Cookie`, `WWW-Authenticate`, …) are never stored. |
| httpCall.description                                                        | (`String`) what the call does, in natural language — shown to an LLM when the call is offered as a tool.                                                                                                                       |
| httpCall.parameters                                                         | (`Map<String, String>`) parameter name → description, for an LLM calling the call as a tool.                                                                                                                                    |
| httpCall.actions                                                            | (`List<String>`) the behavior-rule actions that trigger this call (`"*"` matches any action).                                                                                                                                   |
| httpCall.timeoutInMillis                                                    | (`Integer`) request timeout for this call; unset falls back to `eddi.httpcalls.default-timeout-millis` (30 s).                                                                                                                  |
| httpCall.maxResponseSizeInBytes                                             | (`Integer`) how much of the response body is kept in conversation memory. A larger body is **truncated** (with a warning) rather than failing the call; unset falls back to `eddi.httpcalls.default-max-response-size-bytes` (2 MB). |
| httpCall.forEach                                                            | (`Object`, unset by default) run the call once per element of an array and store every element's result as one array — see [forEach](#foreach--one-call-per-element). Unset sends exactly one request, as before.            |
| httpCall.forEach.pathToTargetArray                                          | (`String`, required) plain dot path to the array in the template data, e.g. `properties.orders` — the notation of `fromObjectPath`, not a template. Nothing there is an empty loop; something other than an array fails the call. |
| httpCall.forEach.iterationObjectName                                        | (`String`, default `item`) the name the current element is bound to — the element itself, so `{item.id}` works.                                                                                                                 |
| httpCall.forEach.templateFilterExpression                                   | (`String`) optional Qute condition per element, e.g. `item.active`; elements for which it is false are skipped.                                                                                                                  |
| httpCall.forEach.maxItems                                                   | (`Integer`, default and ceiling as `batchRequests.maxBatchSize`: `100` / `1000`, see `eddi.httpcalls.batch.*`) more elements refuses the call before anything is sent. Saving a value above the ceiling is refused with `400`. |
| httpCall.forEach.maxConcurrency                                             | (`Integer`, default `1`, at most `16`) items in flight at once, on virtual threads. Saving a value outside `1..16` is refused.                                                                                                    |
| httpCall.forEach.onItemFailure                                              | (`String`, default `continue`) `continue` records each failed item and goes on; `fail` fails the step once every item has finished — after the results are stored. Any other value is refused at save time.                     |
| httpCall.forEach.maxTotalResponseSizeInBytes                                | (`Integer`, default: the call's `maxResponseSizeInBytes`) budget for every item's stored body together; bodies past it are dropped (status kept) and marked `bodyDropped`.                                                         |
| httpCall.preRequest.delayBeforeExecutingInMillis                            | (`Integer`, default `0`) wait this long before sending the request.                                                                                                                                                            |
| httpCall.preRequest.propertyInstructions                                    | (`List`) property instructions run before the request is built, with the same fields and behaviour as `postResponse.propertyInstructions` (no HTTP-code gate).                                                                  |
| httpCall.preRequest.batchRequests.executeCallsSequentially                  | (`Boolean`, default `false`) send the expanded batch requests one after another instead of concurrently.                                                                                                                       |
| httpCall.preRequest.batchRequests.pathToTargetArray                         | (`String`) `JSON` path to the target array to be used as body of requests e.g: "`memory.current.output`". Only honoured when `fireAndForget` is `true`; on a call that waits for a response the batch instruction is ignored and exactly one request is sent — use [`forEach`](#foreach--one-call-per-element) to collect per-element results. |
| httpCall.preRequest.batchRequests.iterationObjectName                       | (`String`) name of the variable to be used for each element of array found in `pathToTargetArray`                                                                                                                                |
| httpCall.preRequest.batchRequests.maxBatchSize                              | (`Integer`, default `100`, at most `1000` — both operator-configurable, see `eddi.httpcalls.batch.*` in the [configuration reference](configuration-reference.md)) most requests the batch may expand into. A target array with more elements refuses the whole call — nothing is sent and the turn fails with a message naming the limit — instead of silently dropping the tail. Saving a value above the ceiling is refused with `400`. |
| httpCall.request.path                                                       | (`String`) path in the `targetServer` of the `httpCall` (e.g /`books`)                                                                                                                                                           |
| httpCall.request.headers                                                    | (`Array`:\<key, value> ) for each `httpCall HTTP header`                                                                                                                                                                         |
| httpCall.request.queryParams                                                | (`Array`: \<key, value>) for each `httpCall` query parameter                                                                                                                                                                     |
| httpCall.request.method                                                     | (`String`) `HTTP` Method of the `httpCall` (e.g `GET`,`POST`,etc...)                                                                                                                                                             |
| httpCall.request.contentType                                                | (`String`) value of the `contentType HTTP header` of the `httpCall`                                                                                                                                                              |
| httpCall.request.body                                                       | (`String`) an escaped `JSON` object that goes in the `HTTP Request` body if needed.                                                                                                                                              |
| httpCall.postResponse.retryApiCallInstruction.maxRetries                    | (`Integer`, default `3`) how often the call is repeated when a retry condition below matches.                                                                                                                                   |
| httpCall.postResponse.retryApiCallInstruction.exponentialBackoffDelayInMillis | (`Integer`, default `1000`) base delay of the exponential backoff between attempts.                                                                                                                                            |
| httpCall.postResponse.retryApiCallInstruction.maxBackoffDelayInMillis       | (`Integer`) upper bound for one backoff delay — the engine clamps it to 30 s in any case.                                                                                                                                      |
| httpCall.postResponse.retryApiCallInstruction.retryOnHttpCodes              | (`List<Integer>`, default `[502, 503]`) response codes that trigger a retry.                                                                                                                                                   |
| httpCall.postResponse.retryApiCallInstruction.responseValuePathMatchers     | (`List`) `{valuePath, equals, contains, trueIfNoMatch}` checks on the response; a match (or, with `trueIfNoMatch`, a non-match) triggers a retry.                                                                                |
| httpCall.postResponse.outputBuildInstructions[]                             | (`List`) builds output items from the response: one item of `outputType` per element of `pathToTargetArray` (bound to `iterationObjectName`, optionally filtered by `templateFilterExpression`), its text rendered from `outputValue`. Without `pathToTargetArray`, `outputValue` is rendered once. The items reach the reply through the output task. |
| httpCall.postResponse.qrBuildInstructions[].pathToTargetArray               | (`String`) path to the array in your `JSON` **response data.**                                                                                                                                                                   |
| httpCall.postResponse.qrBuildInstructions[].iterationObjectName             | (`String`) a variable name that will point to the `TargetArray.`                                                                                                                                                                 |
| httpCall.postResponse.qrBuildInstructions[].quickReplyValue                 | (`String`) `Qute expression` to use as a `quickReply` value.                                                                                                                                                                |
| httpCall.postResponse.qrBuildInstructions[].quickReplyExpressions           | (`String`) `expression` to retrieve a property from `iterationObjectName`.                                                                                                                                                       |
| httpCall.postResponse.propertyInstructions.name                             | (`String`) name of property to be used in templating                                                                                                                                                                             |
| httpCall.postResponse.propertyInstructions.valueString                      | (`String`) a static value (a template) used when `fromObjectPath` is not defined. Typed siblings exist for other value types: `valueInt`, `valueLong`, `valueFloat`, `valueDouble`, `valueBoolean`, `valueObject`, `valueList`. |
| httpCall.postResponse.propertyInstructions.scope                            | <p>(<code>String</code>) one of: </p><p><code>step</code> for this user interaction only, </p><p><code>conversation</code> for the entire conversation, </p><p><code>longTerm</code> across conversations, </p><p><code>secret</code> vaulted — see [Properties](properties.md#secret-properties)</p> |
| httpCall.postResponse.propertyInstructions.visibility                       | (`String`) `self`, `group` or `global` — who can read a `longTerm` property; see [Properties](properties.md#visibility-v6).                                                                                                      |
| httpCall.postResponse.propertyInstructions.fromObjectPath                   | (`String`) path to the value, starting at the call's `responseObjectName` — e.g. `weatherApi.main.temp` (there is no `httpCalls.` root here). The value is response data and is never rendered as a template (only `valueString` is). It is stored **as found, with its type**: a string as a string (or, with `convertToObject` and a value shaped like a JSON object, parsed into an object), a number as `valueInt` / `valueLong` / `valueDouble`, a boolean as a boolean, an object or array as `valueObject` / `valueList`. A path that resolves to nothing sets nothing. |
| httpCall.postResponse.propertyInstructions.toObjectPath                     | (`String`) instead of setting a property, copy the value found at `fromObjectPath` to this path in the template data of the current task.                                                                                       |
| httpCall.postResponse.propertyInstructions.convertToObject                  | (`Boolean`, default `false`) parse a string value shaped like a JSON object (`{...}`) into an object.                                                                                                                          |
| httpCall.postResponse.propertyInstructions.override                         | (`Boolean`, default `true`) `false` leaves an already-set property alone.                                                                                                                                                        |
| httpCall.postResponse.propertyInstructions.runOnValidationError             | (`Boolean`, default `false`) also run the instruction when the call ended in a validation error (an MCP call's error path uses this).                                                                                         |
| httpCall.postResponse.propertyInstructions.httpCodeValidator.runOnHttpCode  | (`Array`: \<Integer> ) a list of http code that enables this property instruction e.g \[`200`]                                                                                                                                   |
| httpCall.postResponse.propertyInstructions.httpCodeValidator.skipOnHttpCode | (`Array`: \<Integer>) list of http codes that skip this property instruction e.g \[`500,501,400`]                                                                                                                              |

A property instruction here runs on the same implementation as a `property.json` instruction, so the two store the same values. A failing one (a template that does not render) no longer disappears into the log: it is logged with the conversation id and recorded in the step under `propertyInstructions:errors`, and the remaining instructions still run. See [Properties](properties.md#via-prepost-request-instructions).

**A missing array is an empty loop.** Quick-reply and output build instructions iterate `pathToTargetArray` with `.orEmpty` appended (unless you already wrote it, or use `?:`, `??` or `or`), so a response that does not contain the array yields no quick replies or output items instead of failing the turn with `Iteration error ... not found`.

### Turn deadline

When the agent sets a [turn deadline](langchain.md#turn-deadline) (`turnDeadlineMs`, or the caller's
`X-EDDI-Turn-Deadline-Ms` header), each call's `timeoutInMillis` is clamped to the time the turn has
left after its reserve, and a `retryApiCallInstruction` retry is skipped when its backoff plus a call
of `min(timeoutInMillis, 3 s)` no longer fits — the last response is then used as if the retries had
been exhausted. The first call is always sent while the deadline has not passed; once it has, the call
fails fast instead of being sent. Without a deadline, nothing changes.

Fire-and-forget calls are not bounded by it: they are built on the turn's thread but sent in the background and never awaited, so they add nothing to the turn's duration. LLM tool calls (including these httpcalls when used as tools) are clamped the same way — a tool is not started once the deadline has passed.

A `forEach` call is bounded per item: each item's timeout is clamped as above when it starts, and an item whose turn comes after the deadline is not sent and is recorded as such. The deploy-time worst-case warning counts a `forEach` call once per round — `maxItems` divided by `maxConcurrency`, rounded up.

### forEach — one call per element

`forEach` runs the call once for every element of an array and stores what each element produced as one array, under the call's `responseObjectName`, like any other response. `postResponse` property instructions, output templates and later calls read it as usual — `{properties.x}` once copied, or `{memory.current.httpCalls.<responseObjectName>}`.

```json
{
  "name": "fetchOrderStatus",
  "actions": ["check_orders"],
  "saveResponse": true,
  "responseObjectName": "orderStatus",
  "forEach": {
    "pathToTargetArray": "properties.orderIds",
    "iterationObjectName": "orderId",
    "maxConcurrency": 4
  },
  "request": { "method": "GET", "path": "/orders/{orderId}" }
}
```

Each element of the stored array describes one item, in the order of the source array whatever order the items finished in:

| Field | Meaning |
| --- | --- |
| `index` | the element's position in the source array (filtered elements leave gaps) |
| `ok` | `true` for a `2xx` with no error |
| `httpCode` | the last attempt's status; absent when no response arrived |
| `body` | the body, stored exactly as a single call stores it (parsed JSON, redacted, capped at `maxResponseSizeInBytes`); for a non-`2xx`, the redacted error text. Absent with `saveResponse: false` |
| `headers` | the response headers, when the call sets `responseHeaderObjectName` (credential headers removed) |
| `error` | why the item failed, written by EDDI |
| `timedOut` | `true` when the request timed out |
| `sent` | `false` when nothing was sent (the request could not be built, or the turn deadline had passed) |
| `attempts` | requests sent for this item |
| `bodyDropped` | `true` when the total size budget was spent before this item |

**Which code `postResponse` sees.** When every item succeeded the step runs `postResponse` with `200`; when any failed, with `207` (HTTP's own Multi-Status) and `{responseObjectName}Error` holds a summary such as `2 of 5 forEach items failed`. Property instructions gated on the default codes (`200`, `201`) therefore run only on a complete result; add `207` to `runOnHttpCode` to process a partial one.

**A failing item never cancels its siblings.** Each item records its own error. `onItemFailure: "fail"` turns a partial result into a failed step, but only after every item has finished and the results are stored, so the calls that did succeed — and may have been paid for — stay readable.

**Retries.** `retryApiCallInstruction` applies per item, to responses (`retryOnHttpCodes`, `responseValuePathMatchers` evaluated against the item's own response). **A request that threw — a timeout above all — is never retried**: the API may have processed it (and billed it) even though no answer arrived. The item is marked `timedOut`.

**Bounds.**

- *Items*: more elements than `maxItems` refuses the whole call before anything is sent, instead of silently dropping the tail.
- *Concurrency*: `maxConcurrency` (default `1`, at most `16`) items in flight at once, on virtual threads. The default sends the load the same calls written one after another would; raise it only for an API that tolerates parallel calls.
- *Time*: the [turn deadline](#turn-deadline), per item.
- *Memory*: every body is capped by `maxResponseSizeInBytes`, and all of them together by `maxTotalResponseSizeInBytes` (default: the same value — the array gets what one response would). Conversation memory is loaded on every turn.

**Security is unchanged.** Every item's request is built on the turn's own thread exactly as a single call's is — templating, `${vault:}`, `${connection:}` and `${caller:}` resolution, the SSRF check, the approval scope — and only the sending happens in parallel. The element is bound as template **data**, never as template source: an element containing `{properties.secret}` is sent as that literal text, and one substituted into the path is a single encoded segment. An item whose request cannot be built (a refused reference, say) is not sent and records why; the others still run. A `forEach` call used as an LLM tool cannot be pinned to an approved request fingerprint, since it sends N requests.

**Not combinable** with `fireAndForget` or `preRequest.batchRequests` — saving such a config is refused. `forEach` does not record each item's request in memory (the log has every one, redacted). httpcalls are not cost-tracked; per-item spend checks will plug into the item admission step.

### HttpCall API endpoints

| HTTP Method | API Endpoint                                    | Request Body    | Response                              |
| ----------- | ----------------------------------------------- | --------------- | ------------------------------------- |
| POST        | `/apicallstore/apicalls`                     | http-call-model | N/A                                   |
| GET         | `/apicallstore/apicalls/descriptors`         | N/A             | list of references to http-call-model |
| DELETE      | `/apicallstore/apicalls/{id}`                | N/A             | N/A                                   |
| GET         | `/apicallstore/apicalls/{id}`                | N/A             | http-call-model                       |
| PUT         | `/apicallstore/apicalls/{id}`                | http-call-model | N/A                                   |
| GET         | `/apicallstore/apicalls/{id}/currentversion` | N/A             | current version number (`text/plain` integer) |
| POST        | `/apicallstore/apicalls/{id}/currentversion` | N/A             | `303` redirect to `/apicallstore/apicalls/{id}?version=<latest>` |

### httpCall Sample

```javascript
{
  "targetServerUrl": "https://api.agent-metrics.com/v1/messages",
  "httpCalls": [
    {
      "name": "sendUserMessageToAnalytics",
      "actions": [
        "send_input_to_analytics"
      ],
      "saveResponse": false,
      "fireAndForget": true,
      "request": {
        "method": "post",
        "queryParams": {
          "token": "<token>"
        },
        "contentType": "application/json",
        "body": "{\"text\": \"{memory.current.input}\",\"message_type\": \"incoming\",\"user_id\": \"{userInfo.userId}\",\"platform\": \"eddi\"}"
      }
    },
    {
      "name": "sendAgentMessageToAnalytics",
      "actions": [
        "send_output_to_analytics"
      ],
      "saveResponse": false,
      "fireAndForget": true,
      "preRequest": {
        "batchRequests": {
          "pathToTargetArray": "memory.current.output",
          "iterationObjectName": "output"
        }
      },
      "request": {
        "method": "post",
        "queryParams": {
          "token": "<token>"
        },
        "contentType": "application/json",
        "body": "{\"text\": \"{output}\",\"message_type\": \"outgoing\",\"user_id\": \"{userInfo.userId}\",\"platform\": \"eddi\"}"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "nameOfPropertyToBeUsedInTemplating",
            "valueString": "StaticValueHereIfFromObjectPathIsNotDefined",
            "scope": "step",
            "fromObjectPath": "savedObjName.something.something",
            "override": true,
            "httpCodeValidator": {
              "runOnHttpCode": [
                200
              ],
              "skipOnHttpCode": [
                0,
                400,
                401,
                402,
                403,
                404,
                409,
                410,
                500,
                501,
                502
              ]
            }
          }
        ],
        "qrBuildInstructions": [
          {
            "pathToTargetArray": "savedObjName.data.topics",
            "iterationObjectName": "topic",
            "templateFilterExpression": "topic.subType != 'specialSubType'",
            "quickReplyValue": "{topic.name}",
            "quickReplyExpressions": "property(topic_id({topic.id}))"
          }
        ]
      }
    }
  ]
}
```

## Step by step example

We will do a step by step example from scratch (**Agent** creation to a simple conversation that uses `httpCall`)

For the sake of simplicity we will use a free weather API to fetch weather of cities by their names ([api.openweathermap.org](http://api.openweathermap.org/)).

### 1 - Create regularDictionnary

> More about regular dictionaries: [Create a dictionary](creating-your-first-agent/README.md#6-create-a-dictionary).

_Request URL_

`POST` `http://localhost:7070/dictionarystore/dictionaries`

_Request Body_

```javascript
{
  "words": [
    {
      "word": "weather",
      "expressions": "trigger(current_weather)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "what is the weather",
      "expressions": "trigger(current_weather)"
    },
    {
      "phrase": "whats the weather",
      "expressions": "trigger(current_weather)"
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`201`

> The `Location` header contains the resource URI, e.g. `eddi://ai.labs.dictionary/dictionarystore/dictionaries/<id>?version=1`

### 2 - Create the behaviorSet

> More about behaviorSets can be found in [Behavior Rules](behavior-rules.md)

_Request URL_

`POST` `http://localhost:7070/rulestore/rulesets`

_Response Body_

`no content`

_Response Code_

`201`

_Request Body_

```javascript
{
  "behaviorGroups": [
    {
      "name": "",
      "behaviorRules": [
        {
          "name": "Ask for City",
          "actions": [
            "ask_for_city"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "trigger(current_weather)"
              }
            }
          ]
        },
        {
          "name": "Current Weather in City",
          "actions": [
            "current_weather_in_city"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "trigger(current_weather)",
                "occurrence": "lastStep"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

> The `Location` header contains the resource URI, e.g. `eddi://ai.labs.rules/rulestore/rulesets/<id>?version=1`

### 3 - Create the **httpCall**

Note that we can pass user input to the http call using _**`{memory.current.input}`**_.

The OpenWeatherMap API key is a credential, so it is not written into the configuration: store it once in the [secrets vault](secrets-vault.md) under the name `openweathermap-appid` and reference it as `${vault:openweathermap-appid}` — it is resolved when the request is sent and never stored in the configuration or the conversation.

_Request URL_

`POST` `http://localhost:7070/apicallstore/apicalls`

_Request Body_

```javascript
{
  "targetServerUrl": "https://api.openweathermap.org/data/2.5/weather",
  "httpCalls": [
    {
      "name": "currentWeather",
      "saveResponse": true,
      "responseObjectName": "currentWeather",
      "actions": [
        "current_weather_in_city"
      ],
      "request": {
        "path": "",
        "headers": {},
        "queryParams": {
          "APPID": "${vault:openweathermap-appid}",
          "units": "metric",
          "q": "{memory.current.input}"
        },
        "method": "get",
        "contentType": "",
        "body": ""
      }
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`201`

> The `Location` header contains the resource URI, e.g. `eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=1`

### 4 - Create the outputSet

> More about outputSet can be found [Output Configuration](output-configuration.md).
>
> Note When you set `"saveResponse" : true` in `httpCall` then you can use `{memory.current.httpCalls.<responseObjectName>}` to access the response data and use Qute ( `{#for}` ) to iterate over `JSON` `arrays` if you have them in your `JSON` response.

_Request URL_

`POST` `http://localhost:7070/outputstore/outputsets`

_Request Body_

```javascript
{
  "outputSet": [
    {
      "action": "ask_for_city",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Which City would you like to know the weather of?"
            }
          ]
        }
      ]
    },
    {
      "action": "current_weather_in_city",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "The current weather situation of {memory.current.input} is {memory.current.httpCalls.currentWeather.weather[0].description} at {memory.current.httpCalls.currentWeather.main.temp} °C"
            }
          ]
        }
      ]
    }
  ]
}
```

_Response Body_

`no content`

_Response Code_

`201`

> The `Location` header contains the resource URI, e.g. `eddi://ai.labs.output/outputstore/outputsets/<id>?version=1`

### 5 - Creating the workflow

> More about workflows: [Create the workflow](creating-your-first-agent/README.md#2-create-the-workflow).
>
> Important Workflow note
>
> - `ai.labs.httpcalls` & `ai.labs.output` must come after `ai.labs.behavior` in the order of the workflow steps
> - `ai.labs.templating` has to be after `ai.labs.output`

_Request URL_

`POST` `http://localhost:7070/workflowstore/workflows`

_Request Body_

```javascript
{
  "workflowSteps": [
    {
      "type": "eddi://ai.labs.parser",
      "extensions": {
        "dictionaries": [
          {
            "type": "eddi://ai.labs.parser.dictionaries.integer"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.decimal"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.punctuation"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.email"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.time"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.ordinalNumber"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.regular",
            "config": {
              "uri": "eddi://ai.labs.dictionary/dictionarystore/dictionaries/{{dictionary_id}}?version=1"
            }
          }
        ],
        "corrections": [
          {
            "type": "eddi://ai.labs.parser.corrections.levenshtein",
            "config": {
              "distance": "2"
            }
          },
          {
            "type": "eddi://ai.labs.parser.corrections.mergedTerms"
          }
        ]
      },
      "config": {}
    },
    {
      "type": "eddi://ai.labs.behavior",
      "config": {
        "uri": "eddi://ai.labs.rules/rulestore/rulesets/{{behaviourset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.httpcalls",
      "config": {
        "uri": "eddi://ai.labs.apicalls/apicallstore/apicalls/{{httpcall_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/{{outputset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.templating",
      "extensions": {},
      "config": {}
    }
  ]
}
```

Response Body

`no content`

Response Code

`201`

> The `Location` header contains the resource URI, e.g. `eddi://ai.labs.workflow/workflowstore/workflows/<id>?version=1`

### 6 - Creating the agent

_Request URL_

`POST` `http://localhost:7070/agentstore/agents`

_Request Body_

```javascript
{
  "workflows": [
    "eddi://ai.labs.workflow/workflowstore/workflows/{{workflow_id}}?version=1"
  ],
  "channels": []
}
```

_Response Body_

`no content`

_Response Code_

`201`

> The `Location` header contains the resource URI, e.g. `eddi://ai.labs.agent/agentstore/agents/<id>?version=1`

### 7 - Deploy the agent

_Request URL_

`POST` `http://localhost:7070/administration/production/deploy/**<agent_id>**?version=1&autoDeploy=true`

_Response Body_

`no content`

_Response Code_

`202`



### 8 - Create the conversation

_Request URL_

`POST` `http://localhost:7070/agents/**<agent_id>**/start?environment=**<env>**`

_Response Body_

`no content`

_Response Code_

`201`

> The `Location` header contains the conversation URI.

### 9 - Say weather

_Request URL_

`POST` `http://localhost:7070/agents/<conversation_id>?returnDetailed=false&returnCurrentStepOnly=true`

_Request Body_

```javascript
{
  "input": "weather"
}
```

_Response Body_

```javascript
{
  "agentId": "5af8b075ba31c023bcb9ef3b",
  "agentVersion": 1,
  "environment": "production",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "weather"
        },
        {
          "key": "actions",
          "value": [
            "ask_for_city"
          ]
        },
        {
          "key": "output:text:ask_for_city",
          "value": "Which City would you like to know the weather of?"
        }
      ],
      "timestamp": 1526247548410
    }
  ]
}
```

_Response Code_

`200`



### 10 - Say "Vienna"

_Request URL_

`POST` `http://localhost:7070/agents/<conversation_id>?returnDetailed=false&returnCurrentStepOnly=true`

_Request Body_

```javascript
{
  "agentId": "5af8b075ba31c023bcb9ef3b",
  "agentVersion": 1,
  "environment": "production",
  "conversationState": "READY",
  "redoCacheSize": 0,
  "conversationSteps": [
    {
      "conversationStep": [
        {
          "key": "input:initial",
          "value": "Vienna"
        },
        {
          "key": "actions",
          "value": [
            "current_weather_in_city"
          ]
        },
        {
          "key": "output:text:current_weather_in_city",
          "value": "The current weather situation of Vienna is clear sky at 17.68 °C"
        }
      ],
      "timestamp": 1526247618080
    }
  ]
}
```

_Response Code_

`200`

## Full Example

> **Run it yourself.** The GitBook-hosted Postman collections that used to be linked here
> were lost in the migration. You do not need them: every request is shown inline above,
> and Postman can import EDDI's own spec directly — **Import → Link →**
> `<your-eddi-host>/openapi` (`http://localhost:7070/openapi` for a local install).
> It is generated from the running build, so unlike a committed collection it cannot go
> out of date. The same spec is browsable at `<your-eddi-host>/q/swagger-ui`.
