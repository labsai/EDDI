#!/bin/sh
# Seeds a minimal demo agent so the Open WebUI model list is not empty.
#
# It builds the agent through EDDI's own REST API rather than calling
# POST /backup/import/initialAgents, which cannot currently import the bundled
# Agent Father on Linux: the ZIP's entry names are correctly forward-slashed,
# but extraction writes them as single files with literal backslashes, so the
# workflow directory is never created and the import 500s. That is a
# pre-existing EDDI defect, unrelated to the /v1 adapter this demo exists to
# show, so the demo routes around it instead of depending on it.
#
# The agent is rule-based and has no LLM, so the demo needs no provider
# credentials and its replies are deterministic.

set -eu

EDDI="${EDDI_URL:-http://eddi:7070}"

# POST a config and echo the Location header EDDI returns.
create() {
    curl -sfS -X POST "$EDDI$1" \
        -H 'Content-Type: application/json' \
        -d "$2" \
        -D - -o /dev/null \
        | tr -d '\r' \
        | awk 'tolower($1) == "location:" { print $2 }'
}

if curl -sf -H "Authorization: Bearer ${EDDI_API_KEY:-}" "$EDDI/v1/models" | grep -q '"id"'; then
    echo "[seed] A model is already exposed — leaving it alone."
    exit 0
fi

# A three-turn flow rather than an echo. An echo proves the transport works but
# nothing about EDDI; this shows the thing the adapter exists to bridge —
# conversation state surviving across turns of a stateless HTTP protocol. Open a
# second chat and it asks your name again, which is the per-chat isolation.
echo "[seed] Creating behaviour rules…"
RULES=$(create /rulestore/rulesets '{
  "behaviorGroups": [{
    "name": "Demo",
    "behaviorRules": [
      {
        "name": "Ask for name",
        "actions": ["ask_name"],
        "conditions": [{
          "type": "actionmatcher",
          "configs": { "actions": "CONVERSATION_START", "occurrence": "lastStep" },
          "conditions": []
        }]
      },
      {
        "name": "Capture name and greet",
        "actions": ["capture_name", "greet_by_name", "conversing"],
        "conditions": [
          {
            "type": "actionmatcher",
            "configs": { "actions": "ask_name", "occurrence": "lastStep" },
            "conditions": []
          },
          {
            "type": "inputmatcher",
            "configs": { "expressions": "*", "occurrence": "currentStep" },
            "conditions": []
          }
        ]
      },
      {
        "name": "Keep chatting",
        "actions": ["chat", "conversing"],
        "conditions": [{
          "type": "actionmatcher",
          "configs": { "actions": "conversing", "occurrence": "lastStep" },
          "conditions": []
        }]
      }
    ]
  }]
}')

# Slot-filling: this is what makes the name outlive the turn it arrived on.
echo "[seed] Creating property setter…"
PROPERTY=$(create /propertysetterstore/propertysetters '{
  "setOnActions": [{
    "actions": ["capture_name"],
    "setProperties": [{
      "name": "userName",
      "valueString": "{memory.current.input}",
      "scope": "conversation",
      "override": true
    }]
  }]
}')

echo "[seed] Creating output…"
OUTPUT=$(create /outputstore/outputsets '{
  "outputSet": [
    {
      "action": "ask_name",
      "timesOccurred": 0,
      "outputs": [{
        "type": "text",
        "valueAlternatives": [{
          "type": "text",
          "text": "Hello! I am an EDDI demo agent, reached over the OpenAI-compatible API. Tell me your name — I will store your next message verbatim, so keep it short."
        }]
      }]
    },
    {
      "action": "greet_by_name",
      "timesOccurred": 0,
      "outputs": [{
        "type": "text",
        "valueAlternatives": [{
          "type": "text",
          "text": "Stored: \"{properties.userName}\". That is now a conversation property and will survive every following turn. Ask me anything, then open a new chat and watch me forget."
        }]
      }]
    },
    {
      "action": "chat",
      "timesOccurred": 0,
      "outputs": [{
        "type": "text",
        "valueAlternatives": [{
          "type": "text",
          "text": "You said: \"{memory.current.input}\" — and I still have \"{properties.userName}\" from earlier. That state lives in EDDI, not in the request."
        }]
      }]
    }
  ]
}')

# The templating step must come last: it resolves the {properties.x} and
# {memory.x} placeholders in the outputs above.
echo "[seed] Creating workflow…"
WORKFLOW=$(create /workflowstore/workflows "{
  \"workflowSteps\": [
    { \"type\": \"eddi://ai.labs.parser\", \"config\": {}, \"extensions\": { \"dictionaries\": [], \"corrections\": [] } },
    { \"type\": \"eddi://ai.labs.behavior\", \"config\": { \"uri\": \"$RULES\" }, \"extensions\": {} },
    { \"type\": \"eddi://ai.labs.property\", \"config\": { \"uri\": \"$PROPERTY\" }, \"extensions\": {} },
    { \"type\": \"eddi://ai.labs.output\", \"config\": { \"uri\": \"$OUTPUT\" }, \"extensions\": {} },
    { \"type\": \"eddi://ai.labs.templating\", \"config\": {}, \"extensions\": {} }
  ]
}")

echo "[seed] Creating agent…"
AGENT=$(create /agentstore/agents "{ \"workflows\": [\"$WORKFLOW\"], \"channels\": [] }")

# eddi://ai.labs.agent/agentstore/agents/<id>?version=<n>
AGENT_ID=$(echo "$AGENT" | sed -e 's#.*/agents/##' -e 's#?.*##')
AGENT_VERSION=$(echo "$AGENT" | sed -e 's#.*version=##')

# Name the descriptor: the adapter derives the model id from it, so without a
# name the dropdown shows a bare hex id twice over. Best-effort — a failure
# here costs readability, not function.
curl -sf -X PATCH \
    "$EDDI/descriptorstore/descriptors/$AGENT_ID?version=$AGENT_VERSION" \
    -H 'Content-Type: application/json' \
    -d '{
      "operation": "SET",
      "document": {
        "name": "EDDI Demo Agent",
        "description": "Rule-based demo agent for the OpenAI-compatible API"
      }
    }' -o /dev/null || echo "[seed] Could not name the descriptor — continuing."

echo "[seed] Deploying agent $AGENT_ID v$AGENT_VERSION…"
curl -sfS -X POST \
    "$EDDI/administration/production/deploy/$AGENT_ID?version=$AGENT_VERSION&waitForCompletion=true" \
    -o /dev/null

# The adapter caches its model catalogue (eddi.openai-compat.model-cache-seconds,
# 30s by default), so a deployment made a moment ago is not visible yet. Poll
# rather than print an empty list and leave the reader thinking it failed.
echo "[seed] Waiting for the model to appear on /v1/models…"
i=0
while [ "$i" -lt 24 ]; do
    MODELS=$(curl -sf -H "Authorization: Bearer ${EDDI_API_KEY:-}" "$EDDI/v1/models" || echo '')
    if echo "$MODELS" | grep -q '"id"'; then
        echo "[seed] Ready. Models exposed on /v1:"
        echo "$MODELS"
        echo
        echo "[seed] Open http://localhost:3000 and pick the model from the dropdown."
        exit 0
    fi
    i=$((i + 1))
    sleep 5
done

echo "[seed] The agent deployed, but no model appeared within 2 minutes."
echo "[seed] Check:  curl -H 'Authorization: Bearer <key>' $EDDI/v1/models"
exit 1
