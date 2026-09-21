#!/bin/sh
# Seeds a minimal demo agent so the Open WebUI model list is not empty.
#
# It builds the agent through EDDI's own REST API. EDDI ships no starter agent
# and has no import-on-startup path, so a fresh instance has an empty model
# list until something creates one.
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

# Idempotency is checked PER AGENT, not "is anything deployed at all".
#
# The MongoDB volume persists, so the common second run is: rule-based agent
# already there, and the user has now set EDDI_DEMO_LLM_API_KEY to add the LLM
# one. A single "a model exists, exit 0" guard silently did nothing in exactly
# that case — the user set their key, re-ran, and got no new model and no
# explanation.
MODELS_JSON=$(curl -sf -H "Authorization: Bearer ${EDDI_API_KEY:-}" "$EDDI/v1/models" || echo '')

# Model ids are <slugified-descriptor-name>-<last 6 of agentId>, so the slug of
# the name set below is a stable prefix to look for.
has_model() {
    echo "$MODELS_JSON" | grep -q "\"$1"
}

seed_rule_based_agent() {
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
}

seed_llm_agent() {
# ── Optional second agent: a real LLM, with its key in the vault ──
# The rule-based agent above proves the transport and the state bridge, but it
# has no model, so it cannot answer questions ABOUT anything — an uploaded PDF
# included. Set EDDI_DEMO_LLM_API_KEY and a second agent is created that can.
#
# The key goes into EDDI's Secrets Vault and the agent config references it as
# ${vault:...}, rather than the key being written into the config. That is not
# ceremony: an agent config is exported, diffed, shown in the Manager UI and
# logged, and a literal key would travel with all of it. The vault keeps the
# plaintext in one encrypted place and hands the config a reference, which
# SecretRedactionFilter then redacts wherever it is printed.
    LLM_TYPE="${EDDI_DEMO_LLM_TYPE:-openai}"
    LLM_MODEL="${EDDI_DEMO_LLM_MODEL:-gpt-4o-mini}"

    echo "[seed] Adding an LLM agent ($LLM_TYPE/$LLM_MODEL)…"
    LLM_RULES=$(create /rulestore/rulesets '{
      "behaviorGroups": [{
        "name": "LLM",
        "behaviorRules": [{
          "name": "Answer",
          "actions": ["send_message"],
          "conditions": [{
            "type": "inputmatcher",
            "configs": { "expressions": "*", "occurrence": "currentStep" },
            "conditions": []
          }]
        }]
      }]
    }')

    # apiKey is a vault REFERENCE, not the key. The \$ escapes keep the shell
    # from trying to expand ${vault:...} itself.
    #
    # {context.openai_system_message} is where the adapter puts a client-supplied
    # system message — which, with RAG_SYSTEM_CONTEXT=true, is where Open WebUI's
    # retrieved document chunks arrive. Referencing it is what lets this agent
    # answer questions about an uploaded file.
    LLM_CONFIG=$(create /llmstore/llms "{
      \"tasks\": [{
        \"actions\": [\"send_message\"],
        \"id\": \"demoChat\",
        \"type\": \"$LLM_TYPE\",
        \"description\": \"Demo LLM agent\",
        \"parameters\": {
          \"apiKey\": \"\${vault:$VAULT_KEY_NAME}\",
          \"modelName\": \"$LLM_MODEL\",
          \"systemMessage\": \"You are a helpful assistant reached through EDDI's OpenAI-compatible API. If the following context is non-empty, answer using it and say which document it came from.\n\nContext:\n{context.openai_system_message}\",
          \"logSizeLimit\": \"-1\",
          \"addToOutput\": \"true\"
        }
      }]
    }")

    LLM_WORKFLOW=$(create /workflowstore/workflows "{
      \"workflowSteps\": [
        { \"type\": \"eddi://ai.labs.parser\", \"config\": {}, \"extensions\": { \"dictionaries\": [], \"corrections\": [] } },
        { \"type\": \"eddi://ai.labs.behavior\", \"config\": { \"uri\": \"$LLM_RULES\" }, \"extensions\": {} },
        { \"type\": \"eddi://ai.labs.llm\", \"config\": { \"uri\": \"$LLM_CONFIG\" }, \"extensions\": {} }
      ]
    }")

    LLM_AGENT=$(create /agentstore/agents "{ \"workflows\": [\"$LLM_WORKFLOW\"], \"channels\": [] }")
    LLM_AGENT_ID=$(echo "$LLM_AGENT" | sed -e 's#.*/agents/##' -e 's#?.*##')
    LLM_AGENT_VERSION=$(echo "$LLM_AGENT" | sed -e 's#.*version=##')

    curl -sf -X PATCH         "$EDDI/descriptorstore/descriptors/$LLM_AGENT_ID?version=$LLM_AGENT_VERSION"         -H 'Content-Type: application/json'         -d '{ "operation": "SET", "document": { "name": "EDDI LLM Demo", "description": "LLM-backed demo agent; provider key held in the vault" } }'         -o /dev/null || echo "[seed] Could not name the LLM descriptor — continuing."

    curl -sfS -X POST \
        "$EDDI/administration/production/deploy/$LLM_AGENT_ID?version=$LLM_AGENT_VERSION&waitForCompletion=true" \
        -o /dev/null
    echo "[seed] LLM agent deployed. Its config holds \${vault:$VAULT_KEY_NAME}, not the key."
}

# Store (or rotate) the provider key. Separate from agent creation because it
# must run even when the agent already exists — otherwise changing
# EDDI_DEMO_LLM_API_KEY and re-running would leave the old key in the vault.
store_vault_key() {
    echo "[seed] Checking the vault is enabled…"
    if ! curl -sf "$EDDI/secretstore/secrets/health" >/dev/null 2>&1; then
        echo "[seed] ERROR: the Secrets Vault is not reachable." >&2
        echo "[seed] EDDI_VAULT_MASTER_KEY must be set on the eddi service." >&2
        exit 1
    fi

    echo "[seed] Storing the provider key in the vault as '$VAULT_KEY_NAME'…"
    # Fail hard rather than fall back to embedding the key: a silent downgrade
    # to a plaintext credential is the wrong way to be helpful.
    curl -sfS -X PUT "$EDDI/secretstore/secrets/default/$VAULT_KEY_NAME" \
        -H 'Content-Type: application/json' \
        -d "{\"value\": \"$EDDI_DEMO_LLM_API_KEY\", \"description\": \"Demo LLM provider key (seeded by seed-demo-agent.sh)\"}" \
        -o /dev/null
}

# ── What to seed ──
VAULT_KEY_NAME="demo-llm-api-key"

if has_model "eddi-demo-agent-"; then
    echo "[seed] Rule-based demo agent already deployed — skipping."
else
    seed_rule_based_agent
fi

if [ -n "${EDDI_DEMO_LLM_API_KEY:-}" ]; then
    store_vault_key
    if has_model "eddi-llm-demo-"; then
        echo "[seed] LLM demo agent already deployed — vault key refreshed, agent left alone."
    else
        seed_llm_agent
    fi
else
    echo "[seed] EDDI_DEMO_LLM_API_KEY not set — skipping the LLM agent."
    echo "[seed] Set it in .env and re-run to add an agent that can actually answer questions."
fi

# The adapter caches its model catalogue (eddi.openai-compat.model-cache-seconds,
# 30s by default), so a deployment made a moment ago is not visible yet. Poll
# rather than print an empty list and leave the reader thinking it failed.
#
# Wait for the specific models expected, not merely for "some model exists".
# The weaker condition was satisfied instantly by the rule-based agent from an
# earlier run, so a freshly added LLM agent was missing from the final listing
# and looked like it had failed — while in fact it just had not left the cache.
EXPECTED="eddi-demo-agent-"
if [ -n "${EDDI_DEMO_LLM_API_KEY:-}" ]; then
    EXPECTED="$EXPECTED eddi-llm-demo-"
fi

echo "[seed] Waiting for the models to appear on /v1/models…"
i=0
while [ "$i" -lt 24 ]; do
    MODELS=$(curl -sf -H "Authorization: Bearer ${EDDI_API_KEY:-}" "$EDDI/v1/models" || echo '')
    MISSING=0
    for PREFIX in $EXPECTED; do
        echo "$MODELS" | grep -q "\"$PREFIX" || MISSING=1
    done
    if [ "$MISSING" -eq 0 ]; then
        echo "[seed] Ready. Models exposed on /v1:"
        echo "$MODELS"
        echo
        echo "[seed] Open ${OPEN_WEBUI_URL:-http://localhost:3000} and pick the model from the dropdown."
        exit 0
    fi
    i=$((i + 1))
    sleep 5
done

echo "[seed] The agents deployed, but these did not appear within 2 minutes: $EXPECTED" >&2
echo "[seed] Check:  curl -H 'Authorization: Bearer <key>' $EDDI/v1/models" >&2
exit 1
