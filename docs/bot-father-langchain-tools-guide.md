# Bot Father - LangChain Tools Configuration Guide

## Quick Start

When creating a new connector bot via Bot Father, you'll now be asked three additional questions about LangChain task features:

### 1. Enable Built-in Tools
**Question:** "Would you like to enable built-in tools (calculator, websearch, datetime, weather, etc.) for this bot?"

**Options:**
- **Yes, enable tools** - Activates AI agent mode with access to built-in tools
- **No, just simple chat** - Keeps simple chat mode (default)

### 2. Tools Whitelist (only if tools enabled)
**Question:** "Which specific tools would you like to enable?"

**Quick Reply Options:**
- **Enable all tools** - Makes all 8 tools available
- **Calculator & Web Search** - Enables only `calculator` and `websearch`
- **Calculator, Web, DateTime** - Enables `calculator`, `websearch`, and `datetime`

**Manual Entry:** You can also type a custom JSON array:
```json
["calculator", "websearch", "datetime", "weather"]
```

### 3. Conversation History Limit
**Question:** "How many conversation turns would you like to include in the context?"

**Quick Reply Options:**
- **10 turns (recommended)** - Balances context and performance
- **20 turns** - More context for complex conversations
- **Unlimited (-1)** - All conversation history (may impact performance)

**Manual Entry:** Type any number:
- `-1` = unlimited history
- `0` = no history
- `10-20` = recommended range

---

## Available Built-in Tools

| Tool | Identifier | Description | Example Use Case |
|------|-----------|-------------|------------------|
| **Calculator** | `calculator` | Safe math evaluation (sandboxed) | "What's 15% tip on $84.50?" |
| **Date/Time** | `datetime` | Get current date, time, timezone | "What time is it in Tokyo?" |
| **Web Search** | `websearch` | Search the web (Wikipedia, news) | "What's the weather forecast today?" |
| **Data Formatter** | `dataformatter` | Format JSON, CSV, XML | "Convert this JSON to CSV" |
| **Web Scraper** | `webscraper` | Extract content from web pages (SSRF-protected) | "Get the content from example.com" |
| **Text Summarizer** | `textsummarizer` | Summarize long text | "Summarize this article" |
| **PDF Reader** | `pdfreader` | Extract text from PDF URLs (SSRF-protected) | "Read this PDF file" |
| **Weather** | `weather` | Get weather information | "What's the weather in Paris?" |

---

## Tool Configuration (Server-Side)

Some tools require API keys or external configuration to function. These are configured via **Environment Variables** or `application.properties` on the EDDI server, not in the bot configuration.

### Web Search Tool
By default, the tool uses **DuckDuckGo** (HTML scraping), which requires no configuration.

To use **Google Custom Search** (more reliable/structured), configure these properties:

```properties
# In application.properties
eddi.tools.websearch.provider=google
eddi.tools.websearch.google.api-key=YOUR_GOOGLE_API_KEY
eddi.tools.websearch.google.cx=YOUR_CUSTOM_SEARCH_ENGINE_ID
```

**Docker Environment Variables:**
- `EDDI_TOOLS_WEBSEARCH_PROVIDER=google`
- `EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=...`
- `EDDI_TOOLS_WEBSEARCH_GOOGLE_CX=...`

### Weather Tool
The weather tool uses **OpenWeatherMap**. You must provide an API key:

```properties
# In application.properties
eddi.tools.weather.openweathermap.api-key=YOUR_OWM_API_KEY
```

**Docker Environment Variables:**
- `EDDI_TOOLS_WEATHER_OPENWEATHERMAP_API_KEY=...`

---

## Configuration Examples

### Example 1: Customer Support Bot with Tools
```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["calculator", "websearch", "datetime"],
  "conversationHistoryLimit": 15
}
```
**Use Case:** Customer support bot that can calculate discounts, search for product info, and provide time-based responses.

### Example 2: Simple Chat Bot (No Tools)
```json
{
  "enableBuiltInTools": false,
  "builtInToolsWhitelist": [],
  "conversationHistoryLimit": 10
}
```
**Use Case:** Basic chat bot for general conversation without external tool access.

### Example 3: Research Assistant with All Tools
```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": [],
  "conversationHistoryLimit": 20
}
```
**Use Case:** Research assistant with access to all tools and extended conversation memory.

### Example 4: Math Tutor (Calculator Only)
```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["calculator"],
  "conversationHistoryLimit": 10
}
```
**Use Case:** Math tutor that can perform calculations but doesn't need web access.

---

## Tool Execution Pipeline

All tool invocations flow through a unified pipeline that provides enterprise-grade controls. These settings can be added to the `langchain.json` task configuration:

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `enableRateLimiting` | boolean | `true` | Token-bucket rate limiting per tool |
| `defaultRateLimit` | int | `100` | Default calls/minute for each tool |
| `toolRateLimits` | map | `{}` | Per-tool overrides, e.g. `{"websearch": 30}` |
| `enableToolCaching` | boolean | `true` | Cache identical tool calls |
| `enableCostTracking` | boolean | `true` | Track cost per conversation |
| `maxBudgetPerConversation` | number | unlimited | Maximum spend per conversation |

### Example: Restrict web search rate and set a budget

```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["calculator", "websearch"],
  "enableRateLimiting": true,
  "defaultRateLimit": 100,
  "toolRateLimits": { "websearch": 20 },
  "enableCostTracking": true,
  "maxBudgetPerConversation": 2.0
}
```

---

## Security

Tools that accept URLs (PDF Reader, Web Scraper) are protected against **SSRF** (Server-Side Request Forgery):

- Only `http://` and `https://` URLs accepted
- Private / internal IP ranges blocked (127.x, 10.x, 192.168.x, 172.16-31.x)
- Cloud metadata endpoints blocked (169.254.169.254, metadata.google.internal)
- Internal hostnames rejected (localhost, *.local, *.internal)

The **Calculator** tool uses a sandboxed recursive-descent parser — no script engine, no code injection risk.

See the [Security documentation](security.md) for full details.

---

## Best Practices

### When to Enable Tools
✅ **Enable tools when:**
- Bot needs to perform calculations
- Bot should search for current information
- Bot needs to access external data
- You want agentic behavior (autonomous tool use)

❌ **Keep tools disabled when:**
- Simple conversational bot
- Controlled, predictable responses needed
- Security/privacy concerns about external access
- Cost optimization (tools may increase API costs)

### Choosing History Limit
- **Short conversations (0-5 turns):** Use for FAQ bots or single-turn interactions
- **Medium conversations (10-15 turns):** Recommended for most use cases
- **Long conversations (20+ turns):** Use for complex problem-solving or tutoring
- **Unlimited (-1):** Only for special cases; may cause performance issues

### Tools Whitelist Strategy
1. **Start specific:** Begin with only the tools you need
2. **Add gradually:** Enable more tools as requirements grow
3. **Monitor usage:** Check which tools are actually being used
4. **Security first:** Only enable tools that match your security requirements

---

## Provider Support

All LLM providers in Bot Father now support these features:

| Provider | Tools Support | History Limit | Notes |
|----------|--------------|---------------|-------|
| OpenAI | ✅ | ✅ | Best tool calling support |
| Anthropic/Claude | ✅ | ✅ | Requires `includeFirstBotMessage: false` |
| Gemini | ✅ | ✅ | Google AI Studio |
| Gemini Vertex | ✅ | ✅ | Google Cloud Vertex AI |
| Hugging Face | ✅ | ✅ | Model-dependent |
| Ollama | ✅ | ✅ | Local models |
| Jlama | ✅ | ✅ | Local Java-based models |

---

## Troubleshooting

### Tools Not Working
**Problem:** Bot doesn't use tools even when enabled
**Solution:**
1. Verify `enableBuiltInTools` is set to `true`
2. Check the system message encourages tool use
3. Ensure the LLM model supports tool calling
4. Try with explicit tool-related prompts

### Too Many Tools Enabled
**Problem:** Bot is using unexpected tools
**Solution:**
1. Use `builtInToolsWhitelist` to restrict tools
2. Adjust system message to guide tool usage
3. Review conversation logs to see tool invocations

### Performance Issues
**Problem:** Bot is slow or timing out
**Solution:**
1. Reduce `conversationHistoryLimit` (try 5-10)
2. Reduce number of enabled tools
3. Increase API timeout in parameters
4. Use faster LLM model

### Context Length Errors
**Problem:** "Context length exceeded" errors
**Solution:**
1. Lower `conversationHistoryLimit`
2. Use model with larger context window
3. Summarize conversation history periodically

---

## API Configuration Reference

When Bot Father creates the langchain configuration, it generates:

```json
{
  "tasks": [{
    "actions": ["send_message"],
    "id": "model-id",
    "type": "provider-type",
    "description": "Integration description",
    "parameters": {
      "systemMessage": "Your bot's system prompt",
      "addToOutput": "true",
      "apiKey": "your-api-key",
      "modelName": "model-name",
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

---

## Next Steps

1. **Create a test bot** using Bot Father with tools enabled
2. **Experiment** with different tool combinations
3. **Monitor** bot behavior and tool usage
4. **Optimize** configuration based on actual usage
5. **Review** the [LangChain Integration Documentation](langchain.md) for advanced features

---

## Related Documentation

- [LangChain Integration](langchain.md) - Complete LangChain task documentation
- [Security](security.md) - SSRF protection, sandboxed evaluation, tool hardening
- [Bot Father Deep Dive](bot-father-deep-dive.md) - Bot Father architecture
- [Behavior Rules](behavior-rules.md) - Understanding behavior rules
- [Output Configuration](output-configuration.md) - Configuring bot outputs

---

**Last Updated:** March 2026  
**EDDI Version:** 5.6.0  
**Bot Father Version:** 3.0.1

