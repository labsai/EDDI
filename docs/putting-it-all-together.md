# Putting It All Together

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

This guide shows how all of EDDI's components work together to create a complete, functional agent. We'll build a real-world example step-by-step, explaining how each piece connects.

## The Big Picture

EDDI agents are composed of interconnected components that flow through the Lifecycle Pipeline:

```
Dictionary → Parser → Behavior Rules → Actions → HTTP Calls / LLM → Output → User
    ↓          ↓           ↓              ↓            ↓              ↓
  Define    Extract    Decide what   Triggers    Fetch data    Format    Response
  words     meaning    to do        execution    or call AI   response
```

Each component is a **separate configuration** that's **combined into workflows**, which are **assembled into agents**.

## Real-World Example: Hotel Booking Agent

Let's build an agent that helps users book hotel rooms. It will:

1. Greet users
2. Ask for city and dates
3. Check availability via API
4. Show options
5. Confirm booking via API

### Component Overview

We'll need:

- **Dictionary**: Define hotel-related vocabulary
- **Parser**: Extract entities (cities, dates)
- **Behavior Rules**: Conversation flow logic
- **Properties**: Store user inputs
- **HTTP Calls**: Check availability and create bookings
- **Output Templates**: Display results dynamically
- **Workflow**: Combine everything
- **Agent**: Reference the workflow

## Step 1: Create the Dictionary

**Purpose**: Teach the agent hotel-related language

```bash
curl -X POST http://localhost:7070/dictionarystore/dictionaries \
  -H "Content-Type: application/json" \
  -d '{
    "lang": "en",
    "words": [
      {
        "word": "hotel",
        "expressions": "entity(hotel)",
        "frequency": 0
      },
      {
        "word": "room",
        "expressions": "entity(room)",
        "frequency": 0
      },
      {
        "word": "book",
        "expressions": "intent(book)",
        "frequency": 0
      },
      {
        "word": "reserve",
        "expressions": "intent(book)",
        "frequency": 0
      },
      {
        "word": "availability",
        "expressions": "intent(check_availability)",
        "frequency": 0
      }
    ],
    "phrases": [
      {
        "phrase": "check availability",
        "expressions": "intent(check_availability)"
      },
      {
        "phrase": "I want to book",
        "expressions": "intent(book)"
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.dictionary/dictionarystore/dictionaries/DICT_ID?version=1`

**How it connects**: Parser will use this dictionary to convert "I want to book a hotel" → `["intent(book)", "entity(hotel)"]`

## Step 2: Create Behavior Rules

**Purpose**: Define conversation logic and when to trigger actions

```bash
curl -X POST http://localhost:7070/rulestore/rulesets \
  -H "Content-Type: application/json" \
  -d '{
    "behaviorGroups": [
      {
        "name": "Onboarding",
        "behaviorRules": [
          {
            "name": "Welcome",
            "conditions": [
              {
                "type": "occurrence",
                "configs": {
                  "maxTimesOccurred": "0",
                  "behaviorRuleName": "Welcome"
                }
              }
            ],
            "actions": ["welcome"]
          }
        ]
      },
      {
        "name": "Booking Flow",
        "behaviorRules": [
          {
            "name": "Check Availability",
            "conditions": [
              {
                "type": "inputmatcher",
                "configs": {
                  "expressions": "intent(check_availability)",
                  "occurrence": "currentStep"
                }
              }
            ],
            "actions": ["httpcall(check-availability)"]
          },
          {
            "name": "Book Room",
            "conditions": [
              {
                "type": "inputmatcher",
                "configs": {
                  "expressions": "intent(book)",
                  "occurrence": "currentStep"
                }
              }
            ],
            "actions": ["httpcall(create-booking)", "booking_confirmed"]
          }
        ]
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.rules/rulestore/rulesets/BEHAVIOR_ID?version=1`

**How it connects**:

- Welcome rule triggers on first message → shows welcome output
- Check Availability rule triggers when the user asks about availability → calls the API. The city and the dates are not part of the rule: the client sends them as context, and Step 3 stores them as properties
- Book Room rule triggers when the user picks a room — the room quick replies built in Step 4 carry `intent(book)` → creates the booking

> Why no `contextmatcher` here? A `contextmatcher` with `contextType: "string"` is an equality test against the `string` value it carries, not a presence test — `"string": "Paris"` would make the rule fire for Paris only. (Omitting the value fails rule-set deserialization, and the deployment ends in `ERROR` instead of `READY`.) Use it to branch on a known value, not to check that a value was sent.

## Step 3: Create Property Configuration

**Purpose**: Extract and store user-provided data

```bash
curl -X POST http://localhost:7070/propertysetterstore/propertysetters \
  -H "Content-Type: application/json" \
  -d '{
    "setOnActions": [
      {
        "actions": ["httpcall(check-availability)"],
        "setProperties": [
          { "name": "city", "fromObjectPath": "context.city", "scope": "conversation" },
          { "name": "checkInDate", "fromObjectPath": "context.checkInDate", "scope": "conversation" },
          { "name": "checkOutDate", "fromObjectPath": "context.checkOutDate", "scope": "conversation" }
        ]
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.property/propertysetterstore/propertysetters/PROPERTY_ID?version=1`

**How it connects**: Property instructions are keyed by the actions that trigger them — when the behavior rule emits `httpcall(check-availability)`, the property setter copies the `city`, `checkInDate` and `checkOutDate` the client sent as context into conversation properties, available as `{properties.city}` etc. in HTTP calls and output templates — also in later turns, when the client no longer sends them. The room the user picks needs no instruction: its quick reply carries the expression `property(room_id(<id>))`, and the property setter turns every `property(...)` expression into a property (`{properties.room_id}`).

## Step 4: Create HTTP Calls

**Purpose**: Integrate with hotel booking API

```bash
curl -X POST http://localhost:7070/apicallstore/apicalls \
  -H "Content-Type: application/json" \
  -d '{
    "targetServerUrl": "https://api.hotels.example.com",
    "httpCalls": [
      {
        "name": "check-availability",
        "saveResponse": true,
        "responseObjectName": "availableRooms",
        "actions": ["httpcall(check-availability)"],
        "request": {
          "method": "GET",
          "path": "/availability",
          "queryParams": {
            "city": "{properties.city}",
            "checkIn": "{properties.checkInDate}",
            "checkOut": "{properties.checkOutDate}"
          }
        },
        "postResponse": {
          "qrBuildInstructions": [
            {
              "pathToTargetArray": "availableRooms.rooms",
              "iterationObjectName": "room",
              "quickReplyValue": "{room.name}",
              "quickReplyExpressions": "intent(book), property(room_id({room.id}))"
            }
          ]
        }
      },
      {
        "name": "create-booking",
        "saveResponse": true,
        "responseObjectName": "bookingConfirmation",
        "actions": ["httpcall(create-booking)"],
        "request": {
          "method": "POST",
          "path": "/bookings",
          "contentType": "application/json",
          "body": "{\"roomId\": \"{properties.room_id}\", \"userId\": \"{userInfo.userId}\", \"checkIn\": \"{properties.checkInDate}\", \"checkOut\": \"{properties.checkOutDate}\"}"
        },
        "postResponse": {
          "propertyInstructions": [
            {
              "name": "bookingId",
              "fromObjectPath": "bookingConfirmation.bookingId",
              "scope": "conversation"
            },
            {
              "name": "totalPrice",
              "fromObjectPath": "bookingConfirmation.totalPrice",
              "scope": "conversation"
            }
          ]
        }
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.apicalls/apicallstore/apicalls/HTTP_ID?version=1`

**How it connects**:

- `check-availability` call is triggered by behavior rule → fetches available rooms → creates quick reply buttons
- `create-booking` call is triggered after user selects room → creates booking → stores booking ID and price as properties. `fromObjectPath` starts at the call's `responseObjectName` (`bookingConfirmation`), and the value is stored with its type — `totalPrice` stays a number

## Step 5: Create Output Templates

**Purpose**: Define agent responses with dynamic data

```bash
curl -X POST http://localhost:7070/outputstore/outputsets \
  -H "Content-Type: application/json" \
  -d '{
    "outputSet": [
      {
        "action": "welcome",
        "outputs": [
          {
            "valueAlternatives": [
              {
                "type": "text",
                "text": "Welcome to Hotel Booking Agent! I can help you find and book hotel rooms. Which city are you interested in?"
              }
            ]
          }
        ]
      },
      {
        "action": "httpcall(check-availability)",
        "outputs": [
          {
            "valueAlternatives": [
              {
                "type": "text",
                "text": "Great! I found {memory.current.httpCalls.availableRooms.rooms.size()} available rooms in {properties.city}. Here are your options:"
              }
            ]
          }
        ]
      },
      {
        "action": "booking_confirmed",
        "outputs": [
          {
            "valueAlternatives": [
              {
                "type": "text",
                "text": "🎉 Booking confirmed! Your booking ID is {properties.bookingId}. Total price: {properties.totalPrice} USD. We'\''ve sent a confirmation email. Have a great stay!"
              }
            ]
          }
        ]
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.output/outputstore/outputsets/OUTPUT_ID?version=1`

**How it connects**:

- `welcome` action → shows greeting
- `httpcall(check-availability)` action → shows room count dynamically from API response
- `booking_confirmed` action → shows booking details from stored properties

## Step 6: Create Workflow

**Purpose**: Bundle all components together

```bash
curl -X POST http://localhost:7070/workflowstore/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "workflowSteps": [
      {
        "type": "eddi://ai.labs.parser",
        "config": {},
        "extensions": {
          "dictionaries": [
            {
              "type": "eddi://ai.labs.parser.dictionaries.regular",
              "config": {
                "uri": "eddi://ai.labs.dictionary/dictionarystore/dictionaries/DICT_ID?version=1"
              }
            }
          ],
          "corrections": []
        }
      },
      {
        "type": "eddi://ai.labs.behavior",
        "config": {
          "uri": "eddi://ai.labs.rules/rulestore/rulesets/BEHAVIOR_ID?version=1",
          "appendActions": true
        }
      },
      {
        "type": "eddi://ai.labs.property",
        "config": {
          "uri": "eddi://ai.labs.property/propertysetterstore/propertysetters/PROPERTY_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.httpcalls",
        "config": {
          "uri": "eddi://ai.labs.apicalls/apicallstore/apicalls/HTTP_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.output",
        "config": {
          "uri": "eddi://ai.labs.output/outputstore/outputsets/OUTPUT_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.templating",
        "config": {}
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.workflow/workflowstore/workflows/WORKFLOW_ID?version=1`

**How it connects**: Workflow defines the order of lifecycle tasks and loads all configurations

## Step 7: Create Agent

**Purpose**: Create the top-level agent entity

```bash
curl -X POST http://localhost:7070/agentstore/agents \
  -H "Content-Type: application/json" \
  -d '{
    "workflows": [
      "eddi://ai.labs.workflow/workflowstore/workflows/WORKFLOW_ID?version=1"
    ]
  }'
```

**Returns**: Agent ID (e.g., `AGENT_ID`)

**How it connects**: Agent references the workflow, which contains all the components

## Step 8: Deploy Agent

```bash
curl -X POST "http://localhost:7070/administration/production/deploy/AGENT_ID?version=1&autoDeploy=true"
```

**Result**: Agent is now active and ready to handle conversations!

## Step 9: Test the Agent

### Initial Conversation

```bash
curl -i -X POST "http://localhost:7070/agents/AGENT_ID/start" \
  -H "Content-Type: application/json" \
  -d '{}'
```

**Response**: `201 Created` with an empty body — the conversation id is returned in the `Location` header as an `eddi://` URI, and `CONV_ID` is its last path segment:

```text
HTTP/1.1 201 Created
Location: eddi://ai.labs.conversation/conversationstore/conversations/CONV_ID
```

### Provide City and Check Availability

```bash
curl -X POST "http://localhost:7070/agents/CONV_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "input": "check availability in Paris",
    "context": {
      "city": {"type": "string", "value": "Paris"},
      "checkInDate": {"type": "string", "value": "2025-06-01"},
      "checkOutDate": {"type": "string", "value": "2025-06-05"}
    }
  }'
```

**What happens internally**:

1. **Parser**: "check availability in Paris" → `["intent(check_availability)"]` (the dictionary has no entry for "in" or "Paris")
2. **Behavior Rules**: Matches the "Check Availability" rule
3. **Actions**: Triggers `httpcall(check-availability)`; the property setter stores `city`, `checkInDate` and `checkOutDate` from the context
4. **HTTP Call**: `GET https://api.hotels.example.com/availability?city=Paris&checkIn=2025-06-01&checkOut=2025-06-05`
5. **Response Processing**: Creates quick reply buttons from room list
6. **Output**: Shows available rooms with dynamic count
7. **Memory**: Stores API response for later use

**Response**:

```json
{
  "conversationOutputs": [
    {
      "output": [
        {
          "type": "text",
          "text": "Great! I found 5 available rooms in Paris. Here are your options:",
          "delay": 0
        }
      ],
      "quickReplies": [
        { "value": "Deluxe Suite", "expressions": "intent(book), property(room_id(101))" },
        { "value": "Standard Room", "expressions": "intent(book), property(room_id(102))" },
        { "value": "Executive Suite", "expressions": "intent(book), property(room_id(103))" }
      ]
    }
  ]
}
```

### Book a Room

```bash
curl -X POST "http://localhost:7070/agents/CONV_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "input": "Deluxe Suite"
  }'
```

The user clicks the "Deluxe Suite" quick reply, which sends its value as the input.

**What happens internally**:

1. **Parser**: "Deluxe Suite" matches the quick reply offered in the previous turn → `["intent(book)", "property(room_id(101))"]`. Only quick replies the agent itself offered (from its output set or a `postResponse`) are matched this way — quick replies a client injects through `context` are displayed but never turned into expressions
2. **Behavior Rules**: Matches the "Book Room" rule
3. **Actions**: Triggers `httpcall(create-booking)` and `booking_confirmed`; the property setter stores `room_id` = `101`
4. **HTTP Call**: `POST https://api.hotels.example.com/bookings` with room details
5. **Response Processing**: Extracts bookingId and totalPrice, stores in properties
6. **Output**: Shows confirmation with dynamic booking details

**Response**:

```json
{
  "conversationOutputs": [
    {
      "output": [
        {
          "type": "text",
          "text": "🎉 Booking confirmed! Your booking ID is BK-12345. Total price: 450 USD. We've sent a confirmation email. Have a great stay!",
          "delay": 0
        }
      ]
    }
  ]
}
```

## How the Components Connect: Visual Flow

```
User: "check availability in Paris"
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 1. PARSER (uses Dictionary)                                 │
│    Input: "check availability in Paris"                     │
│    Output: ["intent(check_availability)"]                   │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. BEHAVIOR RULES                                            │
│    Condition: intent(check_availability)                    │
│    Match: YES                                                │
│    Action: httpcall(check-availability)                      │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. HTTP CALLS                                                │
│    Name: check-availability                                  │
│    URL: GET /availability?city=Paris                         │
│    Response: {rooms: [{id: 101, name: "Deluxe"}, ...]}      │
│    Stores: memory.current.httpCalls.availableRooms          │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. QUICK REPLY BUILDER                                       │
│    Iterates: availableRooms.rooms                           │
│    Creates: Quick reply buttons for each room               │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 5. OUTPUT TEMPLATING                                         │
│    Template: "I found {memory.current.httpCalls.            │
│              availableRooms.rooms.size} rooms in            │
│              {properties.city}"                             │
│    Result: "I found 5 rooms in Paris"                        │
└─────────────────────────────────────────────────────────────┘
    ↓
Response to User with output + quick replies
```

## Key Takeaways

### 1. Components are Modular

Each component (dictionary, behavior rules, HTTP calls, outputs) is:

- Created independently via API
- Versioned separately
- Reusable across multiple agents
- Testable in isolation

### 2. Workflows Define Execution Order

The order of the workflow steps matters:

```
Parser → Behavior Rules → Properties → HTTP Calls → Output → Templating
```

This is the lifecycle pipeline order.

### 3. Behavior Rules are the Orchestrator

Behavior rules decide:

- WHEN to call APIs (`httpcall(check-availability)`)
- WHEN to show outputs (`welcome`, `booking_confirmed`)
- WHICH actions to trigger based on conditions

### 4. Memory is the Connector

Everything stores data in and reads from conversation memory:

- HTTP Calls store responses: `memory.current.httpCalls.availableRooms`
- Properties store extracted data: `{properties.city}` (copied from the client's `context.city`)
- Outputs read data: `{properties.bookingId}`

### 5. Context Bridges External Systems

Your application passes context to inject real-world data:

- User IDs
- Session tokens
- Business state
- Configuration

## Common Patterns

### Pattern 1: Progressive Data Collection

```
Step 1: Ask for city → Store in property
Step 2: Ask for dates → Store in property
Step 3: When all data present → Trigger API call
```

### Pattern 2: API-Then-LLM

```
Step 1: Fetch data via HTTP Call
Step 2: Pass data to LLM with context
Step 3: LLM formats response naturally
```

### Pattern 3: Multi-Step Confirmation

```
Step 1: Show options (quick replies)
Step 2: User selects → Store selection
Step 3: Confirm selection → Trigger action
```

## Next Steps

- **Add LLM Integration**: Use OpenAI to handle natural language queries
- **Add Error Handling**: Create behavior rules for failed API calls
- **Add Validation**: Check date formats, availability before booking
- **Add Conversation Memory**: Store booking history across conversations
- **Export for Reuse**: Export the agent and share with team

## Related Documentation

- [Architecture Overview](architecture.md) - Understand the big picture
- [Developer Quickstart](developer-quickstart.md) - Quick start guide
- [Behavior Rules](behavior-rules.md) - Master decision logic
- [HTTP Calls](httpcalls.md) - API integration details
- [Output Templating](output-templating.md) - Dynamic responses
- [Conversation Memory](conversation-memory.md) - State management
