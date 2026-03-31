# FAQs

## How to...?

## ...add an LLM to my agent?

1. Create a LangChain configuration with your provider settings
2. Add a behavior rule that triggers the LLM action (e.g., `send_to_ai`)
3. Add the LangChain extension to your package/workflow

```json
{
  "tasks": [
    {
      "actions": ["send_to_ai"],
      "id": "openai_chat",
      "type": "openai",
      "parameters": {
        "apiKey": "${eddivault:OPENAI_KEY}",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant",
        "sendConversation": "true",
        "addToOutput": "true"
      }
    }
  ]
}
```

See [LLM Integration](langchain.md) for the complete guide with all 12 supported providers.

---

## ...store API keys securely?

Use the **Secrets Vault**. API keys and other sensitive values are encrypted at rest using envelope encryption (AES-256-GCM + PBKDF2).

**Via the Manager UI:** Navigate to **Secrets** in the sidebar. Enter a key name and value — values are write-only and can never be retrieved through the API.

**Via REST API:**

```bash
curl -X PUT http://localhost:7070/secretstore/secrets/MY_API_KEY \
  -H "Content-Type: text/plain" \
  -d "sk-abc123..."
```

**In LangChain configs,** reference secrets using vault syntax:

```json
{
  "parameters": {
    "apiKey": "${eddivault:MY_API_KEY}"
  }
}
```

See [Secrets Vault](secrets-vault.md) for full documentation.

---

## ...use context to pass data from my app?

Send context with each message:

```bash
curl -X POST http://localhost:7070/agents/conv-123 \
  -H "Content-Type: application/json" \
  -d '{
    "input": "What is my name?",
    "context": {
      "userName": {"type": "string", "value": "John"},
      "userId": {"type": "string", "value": "user-123"}
    }
  }'
```

Access in templates: `{context.userName}`

See [Passing Context Information](passing-context-information.md) for full documentation.

---

## ...set up monitoring?

EDDI exposes Prometheus metrics at `/q/metrics` and includes pre-built Grafana dashboards.

**Quick setup with Docker Compose:**

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up
```

Then open Grafana at `http://localhost:3000` (admin/admin).

See [Metrics & Monitoring](metrics.md) for details.

---

## ...deploy to Kubernetes?

```bash
# One-command quickstart
kubectl apply -f https://raw.githubusercontent.com/labsai/EDDI/main/k8s/quickstart.yaml

# Or use Kustomize overlays / Helm charts for production
kubectl apply -k k8s/overlays/mongodb/
```

See [Kubernetes](kubernetes.md) for complete deployment options.

---

## ...start a conversation with a welcome / intro message?

You will need `behavior rules` and an `outputset` for that.

For the behavior rules, you have three possibilities (ordered by recommendation):

### 1) Match for the action `CONVERSATION_START`

```json
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Welcome",
          "actions": [
            "welcome"
          ],
          "conditions": [
            {
              "type": "actionmatcher",
              "configs": {
                "actions": "CONVERSATION_START"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### 2) Check if the triggered action has never been triggered before

```json
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Welcome",
          "actions": [
            "welcome"
          ],
          "conditions": [
            {
              "type": "actionmatcher",
              "configs": {
                "actions": "welcome",
                "occurrence": "never"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### 3) Check how often this rule has succeeded before

```json
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Welcome",
          "actions": [
            "welcome"
          ],
          "conditions": [
            {
              "type": "occurrence",
              "configs": {
                "maxTimesOccurred": "0",
                "behaviorRuleName": "Welcome"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### Output set:

```json
{
  "outputSet": [
    {
      "action": "welcome",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Some output here...",
              "delay": 3000
            }
          ]
        }
      ],
      "quickReplies": []
    }
  ]
}
```

---

## ...say something based on what the agent previously said?

(Think of a form-like behavior, asking a couple of questions and sending these results somewhere.)

### Check whether a certain `action` had been triggered in the previous conversation step.

```json
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Ask for Name",
          "actions": [
            "ask_for_name"
          ],
          "conditions": [
            {
              "type": "actionmatcher",
              "configs": {
                "actions": "some_previous_action",
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

---

Have a question that is not covered? Drop us an email at contact@labs.ai, we are happy to enhance our documentation!
