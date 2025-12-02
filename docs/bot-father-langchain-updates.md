# Bot Father LangChain Task Feature Updates

## Overview
The Bot Father has been updated to support configuration of the new LangChain task features introduced in EDDI 5.6.0. Users can now configure built-in tools, tool whitelisting, and conversation history limits when creating connector bots.

## Changes Made

### New Configuration Options
All LLM provider connector bots now support three new configuration options:

1. **Enable Built-in Tools** (`enableBuiltInTools`)
   - Boolean flag to enable/disable built-in tools (calculator, websearch, datetime, weather, etc.)
   - Default: `false` (must be explicitly enabled)

2. **Built-in Tools Whitelist** (`builtInToolsWhitelist`)
   - JSON array specifying which specific tools to enable
   - Only shown if built-in tools are enabled
   - Empty array `[]` = enable all tools
   - Example: `["calculator", "websearch", "datetime"]`

3. **Conversation History Limit** (`conversationHistoryLimit`)
   - Integer controlling how many conversation turns to include in context
   - Values: `-1` (unlimited), `0` (none), or positive number (recommended: 10-20)
   - Default: `10`

### Updated Providers
The following connector bot providers have been updated:

1. **OpenAI** (`6740832a2b0f614abcaee7a4`)
   - Supports GPT-4, GPT-4o, and other OpenAI models
   
2. **Anthropic/Claude** (`6740832a2b0f614abcaee7b0`)
   - Supports Claude 3 models (Opus, Sonnet, Haiku)
   
3. **Hugging Face** (`6740832a2b0f614abcaee7aa`)
   - Supports various Hugging Face models
   
4. **Gemini** (`6740832b2b0f614abcaee7b6`)
   - Supports Google Gemini models
   
5. **Gemini Vertex** (`6740832b2b0f614abcaee7bc`)
   - Supports Google Vertex AI Gemini models
   
6. **Ollama** (`6740832b2b0f614abcaee7c2`)
   - Supports local Ollama models
   
7. **Jlama** (`6740832b2b0f614abcaee7c8`)
   - Supports Jlama models

## Files Modified Per Provider

For each provider, the following files were updated:

### 1. Behavior Rules (`*.behavior.json`)
- Added behavior rules to ask for built-in tools configuration
- Added conditional logic to show/skip tools whitelist based on enableBuiltInTools value
- Added behavior rule to ask for conversation history limit
- Updated confirmation flow to include new steps

### 2. Property Configuration (`*.property.json`)
- Added property capture for `enableBuiltInTools` from user input
- Added property capture for `builtInToolsWhitelist` (with default `[]` if tools disabled)
- Added property capture for `conversationHistoryLimit` from user input

### 3. Output Messages (`*.output.json`)
- Added output message asking about built-in tools with quick reply buttons
- Added output message asking about tools whitelist with examples and quick replies
- Added output message asking about conversation history limit with recommendations

### 4. HTTP Calls (`*.httpcalls.json`)
- Updated langchain creation body to include three new parameters:
  - `enableBuiltInTools`: `[(${properties.enableBuiltInTools})]`
  - `builtInToolsWhitelist`: `[(${properties.builtInToolsWhitelist})]`
  - `conversationHistoryLimit`: `[(${properties.conversationHistoryLimit})]`

## User Experience Flow

When creating a new connector bot, users will now see:

1. **Existing prompts** (API key, model name, temperature, timeout)
2. **NEW: "Would you like to enable built-in tools?"**
   - Quick replies: "Yes, enable tools" / "No, just simple chat"
3. **NEW (if tools enabled): "Which specific tools would you like to enable?"**
   - Quick replies: "Enable all tools" / "Calculator & Web Search" / "Calculator, Web, DateTime"
   - Users can also type a custom JSON array
4. **NEW: "How many conversation turns would you like to include in the context?"**
   - Quick replies: "10 turns (recommended)" / "20 turns" / "Unlimited"
5. **Confirmation** and bot creation

## Available Tools

When built-in tools are enabled, the following tools are available:

| Tool Name | Whitelist Value | Description |
|-----------|----------------|-------------|
| Calculator | `calculator` | Perform mathematical calculations |
| Date/Time | `datetime` | Get current date, time, timezone info |
| Web Search | `websearch` | Search the web (includes Wikipedia & News) |
| Data Formatter | `dataformatter` | Format JSON, CSV, XML data |
| Web Scraper | `webscraper` | Extract content from web pages |
| Text Summarizer | `textsummarizer` | Summarize long text |
| PDF Reader | `pdfreader` | Extract text from PDF files |
| Weather | `weather` | Get weather information |

## Example Generated Configuration

When a user creates a bot with tools enabled, the HTTP call will generate a configuration like:

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
    "enableBuiltInTools": true,
    "builtInToolsWhitelist": ["calculator", "websearch"],
    "conversationHistoryLimit": 10
  }]
}
```

## Backward Compatibility

- All changes are additive - existing bot configurations continue to work
- New fields have sensible defaults if not specified
- Users who choose "No, just simple chat" will have `enableBuiltInTools: false` and empty whitelist

## Testing Recommendations

1. Test creating a new OpenAI bot with tools enabled
2. Test creating a new Anthropic bot without tools
3. Verify tools whitelist only shows when tools are enabled
4. Verify all quick reply options work correctly
5. Test custom JSON array input for tools whitelist
6. Test different conversation history limit values

## Related Documentation

- [LangChain Integration Documentation](docs/langchain.md)
- [Bot Father Deep Dive](docs/bot-father-deep-dive.md)
- [LangChainConfiguration.java](src/main/java/ai/labs/eddi/modules/langchain/model/LangChainConfiguration.java)

## Version

Updated for EDDI 5.6.0
Bot Father version: 3.0.1

