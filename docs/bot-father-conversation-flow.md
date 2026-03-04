# Bot Father - New Conversation Flow Diagram

## Updated Conversation Flow (v3.0.1 with LangChain Tools)

```
┌─────────────────────────────────────────────────────────────────┐
│                    USER STARTS BOT CREATION                     │
│              (e.g., "Create an OpenAI bot")                     │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 1: Bot Name                                               │
│  ❓ "What would you like to name your bot?"                    │
│  💬 User: "My Assistant Bot"                                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 2: Bot Intro Message                                      │
│  ❓ "What should be the intro message?"                        │
│  💬 User: "Hello! I'm your AI assistant."                     │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 3: System Prompt                                          │
│  ❓ "What system prompt would you like to use?"                │
│  💬 User: "You are a helpful assistant"                       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 4: API Key                                                │
│  ❓ "Enter the API key you would like to use"                  │
│  💬 User: "sk-..."                                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 5: Model Name                                             │
│  ❓ "What's the model name?"                                   │
│  💬 User: "gpt-4o"                                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 6: Temperature                                            │
│  ❓ "What temperature would you like to set?"                  │
│  💬 User: "0.7"                                               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 7: Timeout                                                │
│  ❓ "What would you like to set for request timeout?"          │
│  💬 User: "15000"                                             │
└─────────────────────────────────────────────────────────────────┘
                              ↓
╔═════════════════════════════════════════════════════════════════╗
║                       🆕 NEW FEATURES                           ║
╚═════════════════════════════════════════════════════════════════╝
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 8: Enable Built-in Tools                        🆕        │
│  ❓ "Would you like to enable built-in tools?"                 │
│                                                                 │
│  🔘 Yes, enable tools                                          │
│  🔘 No, just simple chat                                       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
                    ┌─────────┴─────────┐
                    │                   │
            [if YES]│                   │[if NO]
                    ↓                   ↓
┌──────────────────────────────┐  ┌────────────────────────────┐
│  Step 9a: Tools Whitelist    │  │  Step 9b: Skip Tools       │
│                       🆕      │  │  (Set empty whitelist)     │
│  ❓ "Which tools to enable?" │  │                            │
│                              │  │  Properties set:           │
│  🔘 Enable all tools         │  │  • builtInToolsWhitelist:[]│
│  🔘 Calculator & Web Search  │  └────────────────────────────┘
│  🔘 Calculator, Web, DateTime│                 │
│  💬 ["calculator","datetime"]│                 │
└──────────────────────────────┘                 │
                    │                            │
                    └────────────┬───────────────┘
                                 ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 10: Conversation History Limit                    🆕      │
│  ❓ "How many conversation turns to include in context?"       │
│                                                                 │
│  🔘 10 turns (recommended)                                     │
│  🔘 20 turns                                                   │
│  🔘 Unlimited (-1)                                             │
│  💬 "15"                                                       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Step 11: Confirmation                                          │
│  ❓ "Continue with creating this connector bot?"               │
│                                                                 │
│  🔘 Create the bot!                                            │
│  🔘 Cancel this                                                │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    BOT CREATION PROCESS                         │
│  • Create behavior rules                                        │
│  • Create langchain config (with new params) 🆕                 │
│  • Create output set                                            │
│  • Create package                                               │
│  • Create bot                                                   │
│  • Deploy bot                                                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                     ✅ SUCCESS MESSAGE                          │
│  "It's all done! Your bot was successfully created!"            │
│  • Link to chat with bot                                        │
│  • Link to managed bot API                                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## Quick Reply Options Summary

### Step 8: Enable Built-in Tools
| Button | Value | Description |
|--------|-------|-------------|
| Yes, enable tools | `true` | Enables AI agent mode with tools |
| No, just simple chat | `false` | Standard chat mode only |

### Step 9a: Tools Whitelist (Conditional)
| Button | Value | Result |
|--------|-------|--------|
| Enable all tools | `[]` | All 8 tools available |
| Calculator & Web Search | `["calculator","websearch"]` | Only 2 tools |
| Calculator, Web, DateTime | `["calculator","websearch","datetime"]` | Only 3 tools |

**Manual Entry Example:**
```json
["calculator", "websearch", "datetime", "weather"]
```

### Step 10: Conversation History Limit
| Button | Value | Context Size |
|--------|-------|--------------|
| 10 turns (recommended) | `10` | Last 10 conversation exchanges |
| 20 turns | `20` | Last 20 conversation exchanges |
| Unlimited | `-1` | All conversation history |

---

## Conditional Flow Logic

### Tools Whitelist Decision
```
if (enableBuiltInTools === "true") {
    → Show "ask_for_tools_whitelist"
    → Set builtInToolsWhitelist from user input
} else {
    → Action: "skip_to_conversation_history"
    → Set builtInToolsWhitelist = []
}
```

### Implementation in Behavior Rules
```json
{
  "name": "Ask for tools whitelist",
  "actions": ["ask_for_tools_whitelist"],
  "conditions": [{
    "type": "dynamicvaluematcher",
    "configs": {
      "valuePath": "properties.enableBuiltInTools",
      "valueOperator": "equals",
      "value": "true"
    }
  }]
}
```

---

## Generated Configuration Example

After completing all steps, Bot Father generates this langchain configuration:

```json
{
  "tasks": [{
    "actions": ["send_message"],
    "id": "openai",
    "type": "openai",
    "description": "Integration with OpenAI API",
    "parameters": {
      "systemMessage": "You are a helpful assistant",
      "addToOutput": "true",
      "apiKey": "sk-...",
      "modelName": "gpt-4o",
      "timeout": "15000",
      "temperature": "0.7",
      "logRequests": "true",
      "logResponses": "true"
    },
    "enableBuiltInTools": true,              // 🆕 NEW
    "builtInToolsWhitelist": [               // 🆕 NEW
      "calculator",
      "websearch",
      "datetime"
    ],
    "conversationHistoryLimit": 10          // 🆕 NEW
  }]
}
```

---

## State Management

### Properties Set During Flow

| Step | Property | Source | Scope |
|------|----------|--------|-------|
| 1 | `botName` | User input | conversation |
| 2 | `intro` | User input | conversation |
| 3 | `prompt` | User input | conversation |
| 4 | `apiKey` | User input | conversation |
| 5 | `modelName` | User input | conversation |
| 6 | `temperature` | User input | conversation |
| 7 | `timeout` | User input | conversation |
| 8 🆕 | `enableBuiltInTools` | Quick reply / input | conversation |
| 9 🆕 | `builtInToolsWhitelist` | Quick reply / input / default | conversation |
| 10 🆕 | `conversationHistoryLimit` | Quick reply / input | conversation |

---

## Error Handling & Validation

### User Input Validation
- **API Key:** No validation (passed as-is)
- **Model Name:** No validation (provider-specific)
- **Temperature:** Expected numeric string (0.0-1.0)
- **Timeout:** Expected numeric string (milliseconds)
- **enableBuiltInTools:** Must be "true" or "false"
- **builtInToolsWhitelist:** Must be valid JSON array or empty
- **conversationHistoryLimit:** Must be numeric (-1, 0, or positive)

### Quick Replies Ensure Valid Input
All critical fields have quick reply buttons to ensure valid values.

---

## User Experience Timeline

| Phase | Steps | Duration | User Effort |
|-------|-------|----------|-------------|
| Basic Config | 1-7 | ~2 min | Standard |
| Tools Config 🆕 | 8-10 | +30 sec | Low (quick replies) |
| Confirmation | 11 | ~10 sec | One click |
| Creation | Auto | ~5 sec | None (automated) |
| **Total** | **11 steps** | **~3 min** | **Minimal** |

---

## Comparison: Before vs After

### Before (v3.0.0)
- **Steps:** 8
- **Questions:** 7
- **Tools Support:** ❌
- **History Control:** ❌
- **Agent Mode:** ❌

### After (v3.0.1) 🆕
- **Steps:** 11
- **Questions:** 10
- **Tools Support:** ✅ (8 tools available)
- **History Control:** ✅ (flexible limits)
- **Agent Mode:** ✅ (conditional)

---

**Flow Version:** 3.0.1  
**Last Updated:** 2025  
**Applies to:** All 7 LLM providers

