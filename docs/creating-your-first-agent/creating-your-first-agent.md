# Create a "Hello World" agent

_Prerequisites: Up and Running instance of **EDDI** (see:_ [_Getting started_](../getting-started.md)_)_

## Let's get started

Follow these steps to create the configuration files you will need:

### 1. Creating Output

> [See also Output Configuration.](../output-configuration.md)

You have guessed it correctly, another **`POST`** to **`/outputstore/outputsets`** creates the agent's `Output` with a JSON in the body like this:

```javascript
{
  "outputSet": [
    {
      "action": "CONVERSATION_START",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Hello World!"
            }
          ]
        }
      ]
    }
  ]
}
```

You should again get a return code of **`201`** with a **`URI`** in the **`location` header** referencing the newly created output :

`eddi://ai.labs.output/outputstore/outputsets/`**`<UNIQUE_OUTPUTSET_ID>`**`?version=`**`<OUTPUTSET_VERSION>`**

Example :

`eddi://ai.labs.output/outputstore/outputsets/5a26d97417312628b46119fc?version=1`

### 4. Creating the Workflow

Now we will align the just created `LifecycleTasks` in the `Workflow`. Make a **`POST`** to **`/packagestore/packages`** with a JSON in the body like this:

```javascript
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/<UNIQUE_OUTPUTSET_ID>?version=<OUTPUTSET_VERSION>"
      }
    }
  ]
}
```

### Workflow parameters

| Name                         | Description                                          | Required |
| ---------------------------- | ---------------------------------------------------- | -------- |
| packageextensions            | `Array` of `WorkflowExtension`                       |          |
| WorkflowExtension.type       | possible values, see table below "`Extension Types`" |          |
| WorkflowExtension.extensions | `Array` of `Object`                                  | False    |
| WorkflowExtension.config     | `Config` object, but can be empty.                   | True     |

Extension Types in this examples

| Extension             | Config                                                                                                                                             |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| eddi://ai.labs.output | Object Config contains param `uri` with Link to output set, e.g. `eddi://ai.labs.output/outputstore/outputsets/5a26d97417312628b46119fc?version=1` |

>

`eddi://ai.labs.package/packagestore/packages/<UNIQUE_WORKFLOW_ID>?version=<WORKFLOW_VERSION>`

Example

`eddi://ai.labs.package/packagestore/packages/5a2ae60f17312624f8b8a445?version=1`

> See also the API documentation at [http://localhost:7070/q/swagger-ui](http://localhost:7070/q/swagger-ui)

### 5. Creating an Agent

Make a **`POST`** to **`/agentstore/agents`** with a JSON like this:

```javascript
{
     "packages": [
          "eddi://ai.labs.package/packagestore/packages/<UNIQUE_WORKFLOW_ID>?version=<WORKFLOW_VERSION>"
     ]
}
```

### Agent parameters

| Name     | Description                                    |
| -------- | ---------------------------------------------- |
| packages | `Array` of `String`, references to `Workflows` |

b. You should again get a return code of **`201`** with a `URI` in the `location` header referencing the newly created agent :

`eddi://ai.labs.agent/agentstore/agents/`**`<UNIQUE_AGENT_ID>`**`?version=`**`<AGENT_VERSION>`**

Example:

`eddi://ai.labs.agent/agentstore/agents/5a2ae68a17312624f8b8a446?version=1`

> See also the API documentation at [http://localhost:7070/q/swagger-ui](http://localhost:7070/q/swagger-ui)

### 6. Launching the Agent

Finally, we are ready to let the agent fly. From here on, you have the possibility to let an UI do it for you or you do it step by step.

The UI that automates these steps can be reached here: `/chat/production/`**`<UNIQUE_AGENT_ID>`**

Otherwise via REST:

1.  Deploy the Agent:

    Make a **`POST`** to `/administration/production/deploy/`**`<UNIQUE_AGENT_ID>`**`?version=`**`<AGENT_VERSION>`**

    You will receive a `202` http code.

2.  Since deployment could take a while it has been made **asynchronous**.
3.  Make a **`GET`** to `/administration/production/deploymentstatus/`**`<UNIQUE_AGENT_ID>`**`?version=`**`<AGENT_VERSION>`** to find out the status of deployment.

**`NOT_FOUND`**, **`IN_PROGRESS`**, **`ERROR` and `READY`** is what you can expect to be returned in the body.

1. As soon as the Agent is deployed and has `READY` status, make a **`POST`** to `/agents/`**`<UNIQUE_AGENT_ID>`**/start
   1. You will receive a `201` with the `URI` for the newly created Conversation, like this:
      1. e.g.

         `eddi://ai.labs.conversation/conversationstore/conversations/`**`<UNIQUE_CONVERSATION_ID>`**

2. Now it's time to start talking to our Agent 1. Make a **`POST`** to `/agents/`**`<UNIQUE_AGENT_ID>`**/start`/`**`<UNIQUE_CONVERSATION_ID>`**

**Option 1:** is to hand over the input text as `contentType text/plain`. Include the User Input in the body as `text/plain` (e.g. Hello)&#x20;

&#x20;**Option 2:** is to hand over the input as `contentType application/json`, which also allows you to handover context information that you can use with the eddi configurations 1. Include the User Input in the body as application/json (e.g. Hello)

```json
{
  "input": "some user input"
}
```

1. You have two query params you can use to config the returned output 1. `returnDetailed` - default is false - will return all sub results of the entire conversation steps, otherwise only public ones such as input, action, output & quickreplies 2. `returnCurrentStepOnly` - default is true - will return only the latest conversation step that has just been processed, otherwise returns all conversation steps since the beginning of this conversation
2. The output from the agent will be returned as JSON
3. If you are interested in fetching the **`conversationmemory`** at any given time, make a **`GET`** to `/agents/`**`<UNIQUE_AGENT_ID>`**/start`/`**`<UNIQUE_CONVERSATION_ID>`**`?returnDetailed=true` (the query param is optional, default is false)

> If you made it till here, CONGRATULATIONS, you have created your first Agent with **EDDI** !

By the way you can use the attached **postman collection** below to do all of the steps mentioned above by clicking send on each request in postman.

1. Create outputSet
2. Creating package
3. Creating agent
4. Deploy the agent
5. Create conversation
6. Say Hello to the agent

Download the [Postman collection](../.gitbook/assets/Creating%20and%20chatting%20with%20a%20bot.postman_collection.json) to run through all the steps above.

### External Links

[Using collections in postman](https://thinkster.io/tutorials/testing-backend-apis-with-postman/using-collections-in-postman)
