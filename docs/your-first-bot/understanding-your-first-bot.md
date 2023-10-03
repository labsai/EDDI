---
description: The components of the generated bots
---

# Understanding your first bot

The Bot Father generates EDDI specific configuration in order to deploy a bot that utilises the ChatGPT API. EDDI is an middleware and therefore it enables the user to create a structured flow for parsing and manipulating user input, to be used by different chatbot APIs. The generated bots enable you to take the user input and to prepare the input to use the ChatGPT API without the need to have any knowledge of the API. This approach can be used with any chatbot engine. Therefore EDDI becomes the abstraction layer of your bot infrastructure.

In order to see the configuration necessary for a bot, we are going to open the bot manager that can be found on the dashboard.

<figure><img src="../.gitbook/assets/Screenshot 2023-05-06 at 06.26.52.png" alt=""><figcaption><p>EDDI Dashboard with deployed demo bot</p></figcaption></figure>

After opening the bot manager a new tab opens that shows and overview of all currently configured bots.

<figure><img src="../.gitbook/assets/Screenshot 2023-05-06 at 06.34.07.png" alt=""><figcaption><p>EDDI bot manager overview</p></figcaption></figure>

Click on your created bot ("My first bot") in this example to see the bot configuration

<figure><img src="../.gitbook/assets/Screenshot 2023-05-06 at 06.41.10.png" alt=""><figcaption><p>Configuration overview of the example bot</p></figcaption></figure>

The bot consists of different resources. These resources separate the different functions that are necessary to create a flow. The execution sequence of the packages is:

1. Property (eddi://ai.labs.property)
2. Parser (eddi://ai.labs.parser)
3. Behavior (eddi://ai.labs.parser)
4. HttpCalls (eddi://ai.labs.httpcalls)
5. Output (eddi://ai.labs.output)
6. Templating (eddi://ai.labs.templating)

When a user enters an input all resources are executed in the sequence above. After the execution of all the resources the bot output is generated. The user can then add another input, which triggers the next execution sequence to generate the next output.&#x20;

### Property

This resource holds all necessary properties to be stored for usage. For the case of generated bot these are:

* chatGptApi - the URL of the ChatGPT API
* chatGptModel - the model of ChatGPT that should be used
* chatGptToken - the API key of ChatGPT
* chatGptSystemPrompt - the prompt used to configure ChatGPT
* chatGptIntroPrompt - the promt that is displayed to the user on starting the conversation\


### Parser

This resource parses the user input. It can be configured to understand different phrases. In the example bot there is no parser configuration necessary.

### Behavior

This resource defines behaviors depending on user input, properties, http calls or context through behavior rules. In our example it is configured to take any input and create and action called "send\_message".&#x20;

### HTTP Calls

This resource represents http calls to APIs. It is configured to call the ChatGPT API using the API key, whenever a user input has triggered the "send\_message" action. As all user input triggers the "send\_message" action, the package always calls the ChatGPT API with the user input.

It is also configured to build the output. The ChatGPT API responds with a JSON response. This is automatically parsed and the human readable output of the API is found and converted into an output. This output will be put in the chat.

### Output

All output that is not handled via ChatGPT is created in this resource. In the example bot the only output necessary is the conversation start prompt.

### Templating

This is an internal resource that enables EDDI to substitute template strings with values from the conversation.
