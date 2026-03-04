# Putting It All Together

**Version: ≥5.5.x**

This guide shows how all of EDDI's components work together to create a complete, functional bot. We'll build a real-world example step-by-step, explaining how each piece connects.

## The Big Picture

EDDI bots are composed of interconnected components that flow through the Lifecycle Pipeline:

```
Dictionary → Parser → Behavior Rules → Actions → HTTP Calls / LLM → Output → User
    ↓          ↓           ↓              ↓            ↓              ↓
  Define    Extract    Decide what   Triggers    Fetch data    Format    Response
  words     meaning    to do        execution    or call AI   response
```

Each component is a **separate configuration** that's **combined into packages**, which are **assembled into bots**.

## Real-World Example: Hotel Booking Bot

Let's build a bot that helps users book hotel rooms. It will:
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
- **Package**: Combine everything
- **Bot**: Reference the package

## Step 1: Create the Dictionary

**Purpose**: Teach the bot hotel-related language

```bash
curl -X POST http://localhost:7070/regulardictionarystore/regulardictionaries \
  -H "Content-Type: application/json" \
  -d '{
    "language": "en",
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

**Returns**: `eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/DICT_ID?version=1`

**How it connects**: Parser will use this dictionary to convert "I want to book a hotel" → `["intent(book)", "entity(hotel)"]`

## Step 2: Create Behavior Rules

**Purpose**: Define conversation logic and when to trigger actions

```bash
curl -X POST http://localhost:7070/behaviorstore/behaviorsets \
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
              },
              {
                "type": "contextmatcher",
                "configs": {
                  "contextKey": "city",
                  "contextType": "string"
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
              },
              {
                "type": "contextmatcher",
                "configs": {
                  "contextKey": "selectedRoom",
                  "contextType": "string"
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

**Returns**: `eddi://ai.labs.behavior/behaviorstore/behaviorsets/BEHAVIOR_ID?version=1`

**How it connects**:
- Welcome rule triggers on first message → shows welcome output
- Check Availability rule triggers when user asks about availability AND city is in context → calls API
- Book Room rule triggers when user wants to book AND room is selected → creates booking

## Step 3: Create Property Configuration

**Purpose**: Extract and store user-provided data

```bash
curl -X POST http://localhost:7070/propertysetterstore/propertysetters \
  -H "Content-Type: application/json" \
  -d '{
    "propertyInstructions": [
      {
        "name": "city",
        "fromObjectPath": "input",
        "scope": "conversation"
      },
      {
        "name": "selectedRoom",
        "fromObjectPath": "input",
        "scope": "conversation"
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.property/propertysetterstore/propertysetters/PROPERTY_ID?version=1`

**How it connects**: When user says "Paris", property extractor saves it as `context.city` for use in behavior rules and HTTP calls

## Step 4: Create HTTP Calls

**Purpose**: Integrate with hotel booking API

```bash
curl -X POST http://localhost:7070/httpcallsstore/httpcalls \
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
            "city": "[[${context.city}]]",
            "checkIn": "[[${context.checkInDate}]]",
            "checkOut": "[[${context.checkOutDate}]]"
          }
        },
        "postResponse": {
          "qrBuildInstruction": {
            "pathToTargetArray": "availableRooms.rooms",
            "iterationObjectName": "room",
            "quickReplyValue": "[(${room.name})]",
            "quickReplyExpressions": "property(room_id([(${room.id})]))"
          }
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
          "body": "{\\"roomId\\": \\"[[${context.selectedRoom}]]\\", \\"userId\\": \\"[[${context.userId}]]\\", \\"checkIn\\": \\"[[${context.checkInDate}]]\\", \\"checkOut\\": \\"[[${context.checkOutDate}]]\\"}"
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

**Returns**: `eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/HTTP_ID?version=1`

**How it connects**:
- `check-availability` call is triggered by behavior rule → fetches available rooms → creates quick reply buttons
- `create-booking` call is triggered after user selects room → creates booking → stores booking ID and price

## Step 5: Create Output Templates

**Purpose**: Define bot responses with dynamic data

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
                "text": "Welcome to Hotel Booking Bot! I can help you find and book hotel rooms. Which city are you interested in?"
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
                "text": "Great! I found [[${memory.current.httpCalls.availableRooms.rooms.size()}]] available rooms in [[${context.city}]]. Here are your options:"
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
                "text": "🎉 Booking confirmed! Your booking ID is [[${context.bookingId}]]. Total price: $[[${context.totalPrice}]]. We'\''ve sent a confirmation email. Have a great stay!"
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

## Step 6: Create Package

**Purpose**: Bundle all components together

```bash
curl -X POST http://localhost:7070/packagestore/packages \
  -H "Content-Type: application/json" \
  -d '{
    "packageExtensions": [
      {
        "type": "eddi://ai.labs.parser.dictionaries.regular",
        "extensions": {
          "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/DICT_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.behavior",
        "extensions": {
          "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/BEHAVIOR_ID?version=1"
        },
        "config": {
          "appendActions": true
        }
      },
      {
        "type": "eddi://ai.labs.property",
        "extensions": {
          "uri": "eddi://ai.labs.property/propertysetterstore/propertysetters/PROPERTY_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.httpcalls",
        "extensions": {
          "uri": "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/HTTP_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.output",
        "extensions": {
          "uri": "eddi://ai.labs.output/outputstore/outputsets/OUTPUT_ID?version=1"
        }
      },
      {
        "type": "eddi://ai.labs.templating"
      }
    ]
  }'
```

**Returns**: `eddi://ai.labs.package/packagestore/packages/PACKAGE_ID?version=1`

**How it connects**: Package defines the order of lifecycle tasks and loads all configurations

## Step 7: Create Bot

**Purpose**: Create the top-level bot entity

```bash
curl -X POST http://localhost:7070/botstore/bots \
  -H "Content-Type: application/json" \
  -d '{
    "packages": [
      "eddi://ai.labs.package/packagestore/packages/PACKAGE_ID?version=1"
    ]
  }'
```

**Returns**: Bot ID (e.g., `BOT_ID`)

**How it connects**: Bot references the package, which contains all the components

## Step 8: Deploy Bot

```bash
curl -X POST "http://localhost:7070/administration/unrestricted/deploy/BOT_ID?version=1&autoDeploy=true"
```

**Result**: Bot is now active and ready to handle conversations!

## Step 9: Test the Bot

### Initial Conversation

```bash
curl -X POST "http://localhost:7070/bots/unrestricted/BOT_ID" \
  -H "Content-Type: application/json" \
  -d '{}'
```

**Response**:
```json
{
  "conversationId": "CONV_ID",
  "conversationOutputs": [{
    "output": ["Welcome to Hotel Booking Bot! I can help you find and book hotel rooms. Which city are you interested in?"]
  }]
}
```

### Provide City and Check Availability

```bash
curl -X POST "http://localhost:7070/bots/unrestricted/BOT_ID/CONV_ID" \
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
1. **Parser**: "check availability in Paris" → `["intent(check_availability)", "entity(hotel)"]`
2. **Behavior Rules**: Matches "Check Availability" rule (has intent + city in context)
3. **Actions**: Triggers `httpcall(check-availability)`
4. **HTTP Call**: `GET https://api.hotels.example.com/availability?city=Paris&checkIn=2025-06-01&checkOut=2025-06-05`
5. **Response Processing**: Creates quick reply buttons from room list
6. **Output**: Shows available rooms with dynamic count
7. **Memory**: Stores API response for later use

**Response**:
```json
{
  "conversationOutputs": [{
    "output": ["Great! I found 5 available rooms in Paris. Here are your options:"],
    "quickReplies": [
      {"value": "Deluxe Suite", "expressions": "property(room_id(101))"},
      {"value": "Standard Room", "expressions": "property(room_id(102))"},
      {"value": "Executive Suite", "expressions": "property(room_id(103))"}
    ]
  }]
}
```

### Book a Room

```bash
curl -X POST "http://localhost:7070/bots/unrestricted/BOT_ID/CONV_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "input": "book Deluxe Suite",
    "context": {
      "selectedRoom": {"type": "string", "value": "101"},
      "userId": {"type": "string", "value": "user-789"}
    }
  }'
```

**What happens internally**:
1. **Parser**: "book Deluxe Suite" → `["intent(book)", "entity(room)"]`
2. **Behavior Rules**: Matches "Book Room" rule (has intent + selectedRoom)
3. **Actions**: Triggers `httpcall(create-booking)` and `booking_confirmed`
4. **HTTP Call**: `POST https://api.hotels.example.com/bookings` with room details
5. **Response Processing**: Extracts bookingId and totalPrice, stores in properties
6. **Output**: Shows confirmation with dynamic booking details

**Response**:
```json
{
  "conversationOutputs": [{
    "output": ["🎉 Booking confirmed! Your booking ID is BK-12345. Total price: $450. We've sent a confirmation email. Have a great stay!"]
  }]
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
│    Condition: intent(check_availability) + context.city     │
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
│    Template: "I found [[${availableRooms.rooms.size()}]]    │
│              rooms in [[${context.city}]]"                   │
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
- Reusable across multiple bots
- Testable in isolation

### 2. Packages Define Execution Order
The order in the package matters:
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
- Properties store extracted data: `context.city`
- Outputs read data: `[[${context.bookingId}]]`

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
- **Export for Reuse**: Export the bot and share with team

## Related Documentation

- [Architecture Overview](architecture.md) - Understand the big picture
- [Developer Quickstart](developer-quickstart.md) - Quick start guide
- [Behavior Rules](behavior-rules.md) - Master decision logic
- [HTTP Calls](httpcalls.md) - API integration details
- [Output Templating](output-templating.md) - Dynamic responses
- [Conversation Memory](conversation-memory.md) - State management

