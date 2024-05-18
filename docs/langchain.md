## LangChain Lifecycle Task

The LangChain lifecycle task (using the langchain4j library) allows EDDI to leverage the capabilities of various large language model (LLM) APIs. This task seamlessly integrates with a range of currently supported APIs, including OpenAI's ChatGPT, Hugging Face models, Anthropic Claude, Google Gemini, and Ollama, thereby facilitating advanced natural language processing within EDDI bots.

**Note**: To streamline the initial setup and configuration of the LangChain lifecycle task, you can utilize the "Bot Father" bot. The "Bot Father" bot guides you through the process of creating and configuring tasks, ensuring that you properly integrate the various LLM APIs. By using "Bot Father," you can quickly get your LangChain configurations up and running with ease, leveraging its intuitive interface and automated assistance to minimize errors and enhance productivity.

### Configuration

The LangChain task is configured through a JSON object that defines a list of tasks, where each task can interact with a specific LLM API. These tasks can be tailored to specific use cases, utilizing unique parameters and settings.

#### Configuration Parameters

- **actions**: Defines the actions that the lifecycle task is responsible for
- **id**: A unique identifier for the lifecycle task
- **type**: Specifies the type of API (e.g., `openai`, `huggingface`, `anthropic`, `gemini`, `ollama`)
- **description**: Brief description of what the task accomplishes
- **parameters**: Key-value pairs for API configuration such as API keys, model identifiers, and other API-specific settings

#### Example Configuration

Here’s an example of how to configure a LangChain task for various LLM APIs:

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
        "logResponses": "true"
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
      "actions": ["send_message"],
      "id": "huggingFaceQuery",
      "type": "huggingface",
      "description": "Generates text using Hugging Face's transformers.",
      "parameters": {
        "accessToken": "your-huggingface-access-token",
        "modelId": "llama3",
        "temperature": "0.7",
        "timeout": "15000"
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
        "logResponses": "true"
      }
    }
  ]
}
```

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
        "logResponses": "true"
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
        "logResponses": "true"
      }
    }
  ]
}

```

### API Endpoints

The LangChain task can be managed via specific API endpoints, facilitating easy setup, management, and operation within the EDDI ecosystem.

#### Endpoints Overview

1. **Read JSON Schema**
    - **Endpoint:** `GET /langchainstore/langchains/jsonSchema`
    - **Description:** Retrieves the JSON schema for validating LangChain configurations

2. **List LangChain Descriptors**
    - **Endpoint:** `GET /langchainstore/langchains/descriptors`
    - **Description:** Returns a list of all LangChain configurations with optional filters

3. **Read LangChain Configuration**
    - **Endpoint:** `GET /langchainstore/langchains/{id}`
    - **Description:** Fetches a specific LangChain configuration by its ID

4. **Update LangChain Configuration**
    - **Endpoint:** `PUT /langchainstore/langchains/{id}`
    - **Description:** Updates an existing LangChain configuration

5. **Create LangChain Configuration**
    - **Endpoint:** `POST /langchainstore/langchains`
    - **Description:** Creates a new LangChain configuration

6. **Duplicate LangChain Configuration**
    - **Endpoint:** `POST /langchainstore/langchains/{id}`
    - **Description:** Duplicates an existing LangChain configuration

7. **Delete LangChain Configuration**
    - **Endpoint:** `DELETE /langchainstore/langchains/{id}`
    - **Description:** Deletes a specific LangChain configuration

### Advanced Configurations

Explore advanced configurations such as conditional execution based on previous conversation context, dynamic parameter adjustments, and integration with other EDDI lifecycle tasks for optimized performance.

### Common Issues and Troubleshooting

- **API Key Expiry**: Ensure API keys are valid and renew them before expiry
- **Model Misconfiguration**: Verify model names and parameters to ensure they match those supported by the LLM provider
- **Timeouts and Performance**: Adjust timeout settings based on network performance and API responsiveness
