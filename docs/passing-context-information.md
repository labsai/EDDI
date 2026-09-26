# Passing Context Information

## Overview

**Context** is external data that you pass from your application into EDDI conversations. It's how you inject real-world information—like user profiles, session data, or business state—into your agent's logic without hard-coding it.

### Why Context Matters

Context enables your agents to:

- **Personalize responses**: Use user names, preferences, account details
- **Make business decisions**: Check user roles, subscription status, account balances
- **Maintain session state**: Pass authentication tokens (marked [secret](#secret-context-values)), session IDs
- **Adapt behavior**: Change agent responses based on time of day, location, language
- **Integrate with your systems**: Bring data from your CRM, database, or services

### Context vs Conversation Memory

| Aspect        | Context                     | Conversation Memory            |
| ------------- | --------------------------- | ------------------------------ |
| **Source**    | Your application (external) | EDDI (internal)                |
| **Direction** | Input to EDDI               | Managed by EDDI                |
| **Lifetime**  | Per request                 | Persistent across conversation |
| **Purpose**   | Inject external data        | Store conversation history     |
| **Usage**     | `{context.userName}`        | `{memory.current.input}`       |

### Context Types

EDDI supports four context types:

1. **`string`**: Simple text values

   ```json
   "userRole": {"type": "string", "value": "premium"}
   ```

2. **`object`**: Structured JSON data

   ```json
   "userInfo": {"type": "object", "value": {"name": "John", "age": 30}}
   ```

3. **`expressions`**: Parsed semantic expressions

   ```json
   "intent": {"type": "expressions", "value": "purchase(product)"}
   ```

4. **`array`**: A list of values

   ```json
   "tags": {"type": "array", "value": ["a", "b"]}
   ```

> An `array` context is readable in output templates and HTTP call bodies, but it can never be matched by a `contextmatcher` — see [Behavior Rules → Limitations](behavior-rules.md#limitations). Send the data as an `object` (and match with `objectKeyPath`) if you need to match into it.

### Secret Context Values

Context is stored with the conversation step it arrives with, and it is echoed in the
conversation output. For a value that must never be stored or returned — typically the
caller's credential for a downstream API — add `"secret": true`:

```json
"userToken": {"type": "string", "value": "<the caller's token>", "secret": true}
```

A secret value is usable for the turn it arrives with, exactly like any other context: in
templates (`{context.userToken}`), HTTP call headers and behavior rules. Its lifetime
really is per request:

- It is **never echoed** in the conversation output, not even while the turn is still running.
- When the turn ends — completed, stopped, paused or failed — its stored entry is replaced
  by `<secret context>`, and every copy a template made of it is replaced too: other data of
  the step, the conversation output, the conversation properties (so a `longTerm` property
  never reaches the user memory store), a paused tool-call batch and the turn's audit ledger
  entries.
- A later turn, or tasks that run after a HITL resume, see `<secret context>`. Send the
  value again with every request that needs it. An HTTP call that would send the placeholder
  — in a header, query parameter, body or path — is refused with an error naming where it is,
  instead of reaching the API as a credential that authenticates nobody.

Use it in an HTTP call **header**:

```json
"headers": { "Authorization": "Bearer {context.userToken}" }
```

Keep it out of anything that leaves EDDI while the turn runs: a prompt sends it to the model
provider, a streamed reply reaches the user before the turn ends (the returned and stored reply
is scrubbed), and a query parameter or body is written to the server log by the HTTP call task. Values shorter than 8 characters are
not searched for inside other text; from 4 characters up they are still replaced wherever a property, datum, list element, map key or value,
audit field, or a value inside stored JSON text (a paused tool call's arguments) **is** the value (a PIN copied into a property). Values
under 4 characters, and `true`/`false`, are only removed from their own entry. That exact match applies only to an entry whose whole
value is a string: the fields of a secret **object** are searched for from 8 characters, like any value, but never matched exactly —
its `"tokenType": "Bearer"` or `"port": 8080` would otherwise blank every equal value of the turn. Send a short credential such as a
PIN as its own string entry, not as a field of an object.

`"secret": true` is different from the `secretInput` flag: `secretInput` hides the
**message the user typed** (see [Secrets Vault → Secret Input](secrets-vault.md#secret-input-agent-conversations)),
`secret` hides a **context value your application sends**.

### How Context is Used

Once passed to EDDI, context can be:

- **Matched in Behavior Rules**: Conditions check context values
- **Used in Output Templates**: `{context.userName}`
- **Included in HTTP Call Bodies**: Pass to external APIs
- **Stored as Properties**: Save to conversation memory

### Example Flow

```
Your App → POST /agents/conv456
{
  "input": "What's my account balance?",
  "context": {
    "userId": {"type": "string", "value": "user-789"},
    "accountType": {"type": "string", "value": "premium"}
  }
}

→ EDDI Behavior Rule checks context:
   IF context.accountType = "premium" THEN httpcall(get-balance)

→ HTTP Call uses context:
   GET /api/accounts/{context.userId}/balance

→ Output Template uses context:
   "Hello! Your premium account balance is {memory.current.httpCalls.balance.amount}"

→ Response to Your App:
   "Hello! Your premium account balance is $1,250.00"
```

## Sending Context to Conversations

In this section we will explain how **EDDI** handles the context of a conversation and which data can be passed within the scope of a conversation.

In order to talk to **EDDI** with context, send a **`POST`** request to `/agents/`**`{conversationId}`** (same way as interacting in a normal conversation in EDDI), but this time provide context parameters:

### Send message in a conversation with an Agent REST API Endpoint

| Element                          | Tags                                                                                                                                                                                                                                                                                    |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| HTTP Method                      | `POST`                                                                                                                                                                                                                                                                                  |
| API endpoint                     | `/agents/{conversationId}`                                                                                                                                                                                                                                      |
| {conversationId}                 | (`Path` **parameter**): `String Id` of the **conversation** that you wish to **send** the message to.                                                                                                                                                                                   |
| returnDetailed (Optional)        | (`Query` **parameter**):`Boolean` - Default : `false` Will return all sub results of the entire `conversation steps`, otherwise only public ones such as `input, action, output & quickReplies`.                                                                                        |
| returnCurrentStepOnly (Optional) | (`Query` **parameter**):`Boolean` - Default : `true` Will return only the latest `conversationStep` that has just been processed, otherwise returns all `conversationSteps` since the beginning of this `conversation`.                                                                 |
| Request Body                     | a `JSON` object sent in the request body consists of the usual input text (message to the agent) only this time we are going to provide `context` information through a `key value` data structure ; the Context value must have one of the following : `string, object, expressions or array.` |
|                                  |                                                                                                                                                                                                                                                                                         |

## Example

Here is an example of a `JSON` object of the input data:

```javascript
{
  "input": "",
  "context": {
    "onboardingOfUser": {
      "type": "string",
      "value": "true"
    },
    "userInfo": {
      "type": "object",
      "value": {
        "username": "Barbara"
      }
    }
  }
}
```

> You can also test context parameters in the **EDDI Manager** chat panel at `http://localhost:7070`.
