## Langchain Lifecycle Task

**Version: ≥5.3.x**

The Langchain lifecycle task (using the langchain4j library) allows EDDI to leverage the capabilities of various large language model (LLM) APIs. This task seamlessly integrates with a range of currently supported APIs, including OpenAI's ChatGPT, Hugging Face models, Anthropic Claude, Google Gemini, and Ollama, thereby facilitating advanced natural language processing within EDDI bots.

**Note**: To streamline the initial setup and configuration of the Langchain lifecycle task, you can utilize the "Bot Father" bot. The "Bot Father" bot guides you through the process of creating and configuring tasks, ensuring that you properly integrate the various LLM APIs. By using "Bot Father," you can quickly get your Langchain configurations up and running with ease, leveraging its intuitive interface and automated assistance to minimize errors and enhance productivity.

### Configuration

The Langchain task is configured through a JSON object that defines a list of tasks, where each task can interact with a specific LLM API. These tasks can be tailored to specific use cases, utilizing unique parameters and settings.

#### Configuration Parameters

- **actions**: Defines the actions that the lifecycle task is responsible for.
- **id**: A unique identifier for the lifecycle task.
- **type**: Specifies the type of API (e.g., `openai`, `huggingface`, `anthropic`, `gemini`, `ollama`).
- **description**: Brief description of what the task accomplishes.
- **parameters**: Key-value pairs for API configuration such as API keys, model identifiers, and other API-specific settings.
   - **systemMessage**: Optional message to include in the system context.
   - **prompt**: User input to override before the request to the LLM. If not set or empty, the user input will be taken
   - **sendConversation**: Boolean indicating whether to send the entire conversation or only user input (`true` or `false`, default: `true`).
   - **includeFirstBotMessage**: Boolean indicating whether to include the first bot message in the conversation (`true` or `false`, default: `true`).
   - **logSizeLimit**: Limit for the size of the log (`-1` for no limit).
   - **convertToObject**: Boolean indicating whether to convert the LLM response to an object (`true` or `false`, default: `false`). Note: For this to work, the response from your LLM needs to be in valid JSON format! 
   - **addToOutput**: Boolean indicating whether the LLM output should automatically be added to the output (`true` or `false`, default: `false`).
   

#### Example Configuration

Here’s an example of how to configure a Langchain task for various LLM APIs:

##### OpenAI Configuration

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "enhancedOpenAIQuery",
      "type": "openai",
      "description": "Generates text responses using OpenAI's GPT-4o model, tailored with specific response characteristics.",
      "parameters": {
        "apiKey": "your-openai-api-key",
        "modelName": "gpt-4o",
        "temperature": "0.7",
        "timeout": "15000",
        "logRequests": "true",
        "logResponses": "true",
        "systemMessage": "",
        "sendConversation": "true",
        "includeFirstBotMessage": "true",
        "logSizeLimit": "-1",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

##### Hugging Face Configuration

```json
{
  "tasks": [
    {
      "actions": [
        "send_message"
      ],
      "id": "huggingFaceQuery",
      "type": "huggingface",
      "description": "Generates text using Hugging Face's transformers.",
      "parameters": {
        "accessToken": "your-huggingface-access-token",
        "modelId": "llama3",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "",
        "prompt": "",
        "sendConversation": "true",
        "includeFirstBotMessage": "true",
        "logSizeLimit": "-1",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

##### Anthropic Configuration

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "anthropicQuery",
      "type": "anthropic",
      "description": "Generates text using Anthropic's AI model.",
      "parameters": {
        "apiKey": "your-anthropic-api-key",
        "modelName": "Claude",
        "temperature": "0.7",
        "timeout": "15000",
        "logRequests": "true",
        "logResponses": "true",
        "systemMessage": "",
        "prompt": "",
        "sendConversation": "true",
        "includeFirstBotMessage": "false",
        "logSizeLimit": "-1",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```
Note: Anthropic doesn't allow the first message to be from the bot, therefore `includeFirstBotMessage` should be set to `false` for anthropic api calls.

##### Vertex Gemini Configuration

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "vertexGeminiQuery",
      "type": "gemini",
      "description": "Generates text using Vertex AI Gemini model.",
      "parameters": {
        "publisher": "vertex-ai",
        "projectId": "your-project-id",
        "modelId": "vertex-gemini-large",
        "temperature": "0.7",
        "timeout": "15000",
        "logRequests": "true",
        "logResponses": "true",
        "systemMessage": "",
        "prompt": "",
        "sendConversation": "true",
        "includeFirstBotMessage": "true",
        "logSizeLimit": "-1",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

##### Ollama Configuration

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "ollamaQuery",
      "type": "ollama",
      "description": "Generates text using Ollama's language model.",
      "parameters": {
        "model": "ollama-v1",
        "timeout": "15000",
        "logRequests": "true",
        "logResponses": "true",
        "systemMessage": "",
        "prompt": "",
        "sendConversation": "true",
        "includeFirstBotMessage": "true",
        "logSizeLimit": "-1",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

### API Endpoints

The Langchain task can be managed via specific API endpoints, facilitating easy setup, management, and operation within the EDDI ecosystem.

#### Endpoints Overview

1. **Read JSON Schema**
   - **Endpoint:** `GET /langchainstore/langchains/jsonSchema`
   - **Description:** Retrieves the JSON schema for validating Langchain configurations

2. **List Langchain Descriptors**
   - **Endpoint:** `GET /langchainstore/langchains/descriptors`
   - **Description:** Returns a list of all Langchain configurations with optional filters

3. **Read Langchain Configuration**
   - **Endpoint:** `GET /langchainstore/langchains/{id}`
   - **Description:** Fetches a specific Langchain configuration by its ID

4. **Update Langchain Configuration**
   - **Endpoint:** `PUT /langchainstore/langchains/{id}`
   - **Description:** Updates an existing Langchain configuration

5. **Create Langchain Configuration**
   - **Endpoint:** `POST /langchainstore/langchains`
   - **Description:** Creates a new Langchain configuration

6. **Duplicate Langchain Configuration**
   - **Endpoint:** `POST /langchainstore/langchains/{id}`
   - **Description:** Duplicates an existing Langchain configuration

7. **Delete Langchain Configuration**
   - **Endpoint:** `DELETE /langchainstore/langchains/{id}`
   - **Description:** Deletes a specific Langchain configuration

Sure, here is the extended section for the configuration options:

### Extended Configuration Options

The LangChain lifecycle task offers extended configuration options to fine-tune the behavior of tasks before and after they interact with LLM APIs. These configurations help in managing properties, handling responses, and controlling retries.

#### Example of Extended Configuration

Below is an example configuration showcasing more advanced options such as `preRequest`, `postResponse`, and `retryHttpCallInstruction`.

```json
{
  "tasks": [
    {
      "id": "task_1",
      "type": "example_type",
      "description": "This is an example task description",
      "actions": [
        "action_1",
        "action_2"
      ],
      "preRequest": {
        "propertyInstructions": [
          {
            "name": "exampleProperty",
            "valueString": "exampleValue",
            "scope": "step"
          }
        ]
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "exampleResponseProperty",
            "valueString": "responseValue",
            "scope": "conversation"
          }
        ],
        "outputBuildInstructions": [
          {
            "pathToTargetArray": "response.data",
            "iterationObjectName": "item",
            "outputType": "exampleType",
            "outputValue": "exampleOutputValue"
          }
        ],
        "qrBuildInstructions": [
          {
            "pathToTargetArray": "response.quickReplies",
            "iterationObjectName": "item",
            "quickReplyValue": "exampleQuickReplyValue",
            "quickReplyExpressions": "exampleExpression"
          }
        ]
      },
      "parameters": {
        "apiKey": "<apiKey>"
      }
    }
  ]
}
```

#### Configuration Parameters Explained

- **preRequest.propertyInstructions**: Defines properties to be set before making the request to the LLM API. Each instruction specifies:
    - **name**: The property name.
    - **valueString**: The value to be assigned to the property.
    - **scope**: The scope of the property (e.g., `step`, `conversation`, `longTerm`).

- **postResponse.propertyInstructions**: Defines properties to be set based on the response from the LLM API. Each instruction specifies:
    - **name**: The property name.
    - **valueString**: The value to be assigned to the property.
    - **scope**: The scope of the property (e.g., `step`, `conversation`, `longTerm`).

- **postResponse.outputBuildInstructions**: Configures how the response should be transformed into output. This is an alternative to `addToOutput` if you want to manipulate the llm results before adding them to the bot output. 
  Each instruction specifies:
    - **pathToTargetArray**: The path to the array in the response where data is located.
    - **iterationObjectName**: The name of the object for iterating over the array.
    - **outputType**: The type of output to generate.
    - **outputValue**: The value to be used for output.

- **postResponse.qrBuildInstructions**: Configures quick replies based on the response. Each instruction specifies:
    - **pathToTargetArray**: The path to the array in the response where quick replies are located.
    - **iterationObjectName**: The name of the object for iterating over the array.
    - **quickReplyValue**: The value for the quick reply.
    - **quickReplyExpressions**: The expressions for the quick reply.

This extended configuration provides more control and flexibility in managing tasks and handling responses, ensuring that your LangChain tasks operate efficiently and effectively.

### Common Issues and Troubleshooting

- **API Key Expiry**: Ensure API keys are valid and renew them before expiry.
- **Model Misconfiguration**: Verify model names and parameters to ensure they match those supported by the LLM provider.
- **Timeouts and Performance**: Adjust timeout settings based on network performance and API responsiveness.
