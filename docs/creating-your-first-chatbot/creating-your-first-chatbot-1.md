# Create a bot that reacts to user inputs

_Prerequisites: Up and Running instance of **EDDI** (see:_ [_Getting started_](../getting-started.md)_)_

## Let's get started

Follow these steps to create the configuration files you will need:

### **1. Creating a Regular Dictionary inside Parser**

> See also [Semantic Parser](../semantic-parser.md)

Create regular dictionaries in order to store custom words and phrases. A dictionary is there to map user input to expressions, which are later used in `Behavior Rules`. A **`POST`** to **`/regulardictionarystore/regulardictionaries`** with a JSON in the body like this:

```javascript
{
  "words": [
    {
      "word": "hello",
      "expressions": "greeting(hello)",
      "frequency": 0
    },
    {
      "word": "hi",
      "expressions": "greeting(hi)",
      "frequency": 0
    },
    {
      "word": "bye",
      "expressions": "goodbye(bye)",
      "frequency": 0
    },
    {
      "word": "thanks",
      "expressions": "thanks(thanks)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "good afternoon",
      "expressions": "greeting(good_afternoon)"
    },
    {
      "phrase": "how are you",
      "expressions": "how_are_you"
    }
  ]
}
```

Example using **`CURL`**:

```bash
curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' -d '{ \
"language" : "en", \
"words" : [ \
{ \
"word" : "hello", \
"expressions" : "greeting(hello)", \
"frequency" : 0 \
}, \
{ \
"word" : "hi", \
"expressions" : "greeting(hi)", \
"frequency" : 0 \
}, \
{ \
"word" : "bye", \
"expressions" : "goodbye(bye)", \
"frequency" : 0 \
}, \
{ \
"word" : "thanks", \
"expressions" : "thanks(thanks)", \
"frequency" : 0 \
} \
], \
"phrases" : [ \
{ \
"phrase" : "good afternoon", \
"expressions" : "greeting(good_afternoon)" \
}, \
{ \
"phrase" : "how are you", \
"expressions" : "how_are_you" \
} \
] \
}' 'http://localhost:7070/regulardictionarystore/regulardictionaries'
```

### Dictionary parameters

| Name               | Description                                                                                          | Required |
| ------------------ | ---------------------------------------------------------------------------------------------------- | -------- |
| words              | `Array` of `Word`                                                                                    |          |
| phrases            | `Array` of `Phrase`                                                                                  |          |
| Word.word          | `String`, single word, no spaces.                                                                    | True     |
| Word.expressions   | `String`, "greeting(hello)": "greeting" is the category of this expression and "hello" is an entity. |          |
| Word.frequency     | `int`, Used for a randomizer                                                                         |          |
| Phrase.phrase      | `String`, Spaces allowed                                                                             | True     |
| Phrase.expressions | `String`, "greeting(hello)": "greeting" is the category of this expression and "hello" is an entity. |          |

> The returned URI is a reference for this specific resource. This resource will be referenced in the bot definition.

### **2. Creating Behavior Rules**

> See also Behavior Rules

Next, create a `behaviorRule` resource to configure the decision making a. Make a **`POST`** to **`/behaviorstore/behaviorsets`** with a JSON in the body like this:

```javascript
{
  "behaviorGroups": [
    {
      "name": "Smalltalk",
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
        },
        {
          "name": "Greeting",
          "actions": [
            "greet"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "greeting(*)",
                "occurrence": "currentStep"
              }
            }
          ]
        },
        {
          "name": "Goodbye",
          "actions": [
            "say_goodbye",
            "CONVERSATION_END"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "goodbye(*)"
              }
            }
          ]
        },
        {
          "name": "Thank",
          "actions": [
            "thank"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "thank(*)"
              }
            }
          ]
        },
        {
          "name": "how are you",
          "actions": [
            "how_are_you"
          ],
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "how_are_you"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### Behavior Rules parameters

| Name                         | Description                                                                                                                                                                                                                                                                                                                                       |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
|  BehaviorRule.name           | `String`, e.g. "Smalltalk"                                                                                                                                                                                                                                                                                                                        |
| BehaviourGroup.behaviorRules | `Array` of `BehaviorRule`                                                                                                                                                                                                                                                                                                                         |
|  BehaviorRule.name           | `String`, e.g. "Greeting"                                                                                                                                                                                                                                                                                                                         |
| BehaviorRule.actions         | `Array` of `String`, e.g. "greet" or "CONVERSATION\_END"                                                                                                                                                                                                                                                                                          |
| BehaviorRule.conditions      | `Array` of `RuleChild`                                                                                                                                                                                                                                                                                                                            |
| RuleChild.type               | <p><code>String</code>, allowed values: </p><p>—>"<code>inputmatcher</code>" (has params: "<code>expressions</code>" (<code>Array</code> of <code>String</code>( and "<code>occurrence</code>")</p><p> —>"<code>negation</code>" (<code>BehaviorExtension</code> object, has params: "<code>conditions</code>" and "<code>occurrence</code>")</p> |
| RuleChild.values             | <p><code>HashMap</code>, allowed values: </p><p>—>"<code>expressions</code>": <code>String</code>, mandatory. Expression e.g. "greeting(*)" or "how_are_you" </p><p>—>"<code>occurrence</code>": <code>String</code>, optional. Allowed values "<code>currentStep</code>"</p>                                                                     |
| Negation.conditons           | `Array` of `NegationChild`                                                                                                                                                                                                                                                                                                                        |
| NegationChild.type           | `String` e.g. "`occurrence`"                                                                                                                                                                                                                                                                                                                      |
| NegationChild.values         | <p>HashMap, allowed values: </p><p>—>"<code>maxTimesOccurred</code>": <code>String</code>, e.g. 1 </p><p>—>"<code>minTimesOccurred</code>": <code>String</code>, e.g. 1 </p><p>—>"<code>behaviorRuleName</code>": <code>String</code></p>                                                                                                         |

You should again get a return code of **`201`** with a **`URI`** in the **`location` header** referencing the newly created `Behavior Rules`:

`eddi://ai.labs.behavior/behaviorstore/behaviorsets/`**`<UNIQUE_BEHAVIOR_ID>`**`?version=`**`<BEHAVIOR_VERSION>`**

Example:

`eddi://ai.labs.behavior/behaviorstore/behaviorsets/5a26d8fd17312628b46119fb?version=1`

### 3. Creating Output

> [See also Output Configuration.](../output-configuration.md)

You have guessed it correctly, another **`POST`** to **`/outputstore/outputsets`** creates the bot's `Output` with a JSON in the body like this:

```javascript
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
              "text": "Welcome!"
            }
          ]
        },
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "My name is E.D.D.I"
            }
          ]
        }
      ],
      "quickReplies": [
        {
          "value": "Hi EDDI",
          "expressions": "greeting(hi)"
        },
        {
          "value": "Bye EDDI",
          "expressions": "goodbye(bye)"
        }
      ]
    },
    {
      "action": "greet",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Hi there! Nice to meet up! :-)"
            },
            {
              "type": "text",
              "text": "Hey you!"
            }
          ]
        }
      ]
    },
    {
      "action": "greet",
      "timesOccurred": 1,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Did we already say hi ?! Well, twice is better than not at all! ;-)"
            }
          ]
        }
      ]
    },
    {
      "action": "say_goodbye",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "See you soon!"
            }
          ]
        }
      ]
    },
    {
      "action": "thank",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Your Welcome!"
            }
          ]
        }
      ]
    },
    {
      "action": "how_are_you",
      "timesOccurred": 0,
      "outputs": [
        {
          "valueAlternatives": [
            {
              "type": "text",
              "text": "Pretty good.. having lovely conversations all day long.. :-D"
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

### 4. Creating the Package

Now we will align the just created `LifecycleTasks` in the `Package`. Make a **`POST`** to **`/packagestore/packages`** with a JSON in the body like this:

```javascript
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.parser",
      "extensions": {
        "dictionaries": [
          {
            "type": "eddi://ai.labs.parser.dictionaries.integer"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.decimal"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.punctuation"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.email"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.time"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.ordinalNumber"
          },
          {
            "type": "eddi://ai.labs.parser.dictionaries.regular",
            "config": {
              "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/<UNIQUE_DICTIONARY_ID>?version=<DICTIONARY_VERSION>"
            }
          }
        ],
        "corrections": [
          {
            "type": "eddi://ai.labs.parser.corrections.stemming",
            "config": {
              "language": "english",
              "lookupIfKnown": "false"
            }
          },
          {
            "type": "eddi://ai.labs.parser.corrections.levenshtein",
            "config": {
              "distance": "2"
            }
          },
          {
            "type": "eddi://ai.labs.parser.corrections.mergedTerms"
          }
        ]
      },
      "config": {}
    },
    {
      "type": "eddi://ai.labs.behavior",
      "config": {
        "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/<UNIQUE_BEHAVIOR_ID>?version=<BEHAVIOR_VERSION>"
      }
    },
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/<UNIQUE_OUTPUTSET_ID>?version=<OUTPUTSET_VERSION>"
      }
    }
  ]
}
```

### Package parameters

| Name                        | Description                                          | Required |
| --------------------------- | ---------------------------------------------------- | -------- |
| packageextensions           | `Array` of `PackageExtension`                        |          |
| PackageExtension.type       | possible values, see table below "`Extension Types`" |          |
| PackageExtension.extensions | `Array` of `Object`                                  | False    |
| PackageExtension.config     | `Config` object, but can be empty.                   | True     |

Extension Types

| Extension               | Config                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| eddi://ai.labs.parser   | <p><code>Dictionaries</code> and/or corrections </p><p>Object "<code>extensions</code>" can contain "<code>dictionaries</code>" (<code>Array</code> of <code>Dictionary</code>) and/or "<code>corrections</code>" (<code>Array</code> of <code>Correction</code>) </p><p>Object "<code>Dictionary</code>" has params "<code>type</code>" and "<code>config</code>" (optional) </p><p><code>Dictionary.type</code> can reference <code>Regular-Dictionaries</code> "<code>eddi://ai.labs.parser.dictionaries.regular</code>" (needs param "<code>config.uri</code>") or be one of the <strong>EDDI</strong> out of the box types: </p><p>—>"<code>eddi://ai.labs.parser.dictionaries.integer</code>" </p><p>—>"<code>eddi://ai.labs.parser.dictionaries.decimal</code>" </p><p>—>"<code>eddi://ai.labs.parser.dictionaries.punctuation</code>" </p><p>—>"<code>eddi://ai.labs.parser.dictionaries.email</code>" </p><p>—>"<code>eddi://ai.labs.parser.dictionaries.time</code>" </p><p>—>"<code>eddi://ai.labs.parser.dictionaries.ordinalNumber</code>"</p><p>Object "<code>Correction</code>" has params "<code>type</code>" and "<code>config</code>" (optional)</p><p><code>Correction.type</code> can reference one of the EDDI out of the box types: </p><p>—>"<code>eddi://ai.labs.parser.corrections.stemming</code>": Object "<code>config</code>" has params "<code>language</code>" (<code>String</code> e.g. "english") and "<code>lookupIfKnown</code>" (<code>Boolean</code>) </p><p>—>"<code>eddi://ai.labs.parser.corrections.levenshtein</code>": Object "<code>config</code>" has param "<code>distance</code>" (Integer, e.g. 2) </p><p>—>"<code>eddi://ai.labs.parser.corrections.mergedTerms</code>"</p> |
| eddi://ai.labs.behavior | Object `Config` contains param `uri` with Link to a behavior set, e.g. `eddi://ai.labs.behavior/behaviorstore/behaviorsets/5a26d8fd17312628b46119fb?version=1`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| eddi://ai.labs.output   | Object Config contains param `uri` with Link to output set, e.g. `eddi://ai.labs.output/outputstore/outputsets/5a26d97417312628b46119fc?version=1`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |

> New

Now you can use the new feature of defining properties in the package definition : This can be used by introducing an extension with `type` `eddi://ai.labs.property` which has the `config` model as follows:

```javascript
{
  "type": "eddi://ai.labs.property",
  "config": {
    "setOnActions": [
      {
        "actions": "string",
        "setProperties": [
          {
            "name": "string",
            "fromObjectPath": "string",
            "scope": "string"
          }
        ]
      }
    ]
  }
}
```

### Description of eddi://ai.labs.property model

| Name                                      | Description                                                                                            |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| setOnActions.actions                      | (`string`) defines which for which actions (triggered by BehaviorRules) these Properties should be set |
| setOnActions.setProperties                | (`Array` <`Property`>: ) must respect the `Property`model: `name`, `fromObjectPath` and `scope`.       |
| setOnActions.setProperties.name           | (`string`) name of the `Property`.                                                                     |
| setOnActions.setProperties.fromObjectPath | (`string`) path to the json object.                                                                    |
| setOnActions.setProperties.scope          | (`string`) Possible values `step`, `conversation` and `longTerm` .                                     |

#### Example of eddi://ai.labs.property

```javascript
{
  "packageExtensions": [
   ...
    {
      "type": "eddi://ai.labs.property",
      "config": {
        "setOnActions": [
          {
            "actions": "currentWeather",
            "setProperties": [
              {
                "name": "city",
                "fromObjectPath": "memory.current.input",
                "scope": "longTerm"
              }
            ]
          }
        ]
      }
    },
   ...
    {
      "type": "eddi://ai.labs.property",
      "config": {
        "setOnActions": [
          {
            "actions": "currentWeather",
            "setProperties": [
              {
                "name": "currentWeather",
                "fromObjectPath": "memory.current.httpCalls.currentWeather",
                "scope": "conversation"
              }
            ]
          }
        ]
      }
    },
   ...
  ]
}
```

You should again get a return code of `201` with an `URI` in the location header referencing the newly created package format

`eddi://ai.labs.package/packagestore/packages/<UNIQUE_PACKAGE_ID>?version=<PACKAGE_VERSION>`

Example

`eddi://ai.labs.package/packagestore/packages/5a2ae60f17312624f8b8a445?version=1`

> See also the API documentation at [http://localhost:7070/view#!/configurations/createPackage](http://localhost:7070/view#!/configurations/createPackage)

### 5. Creating a Bot

Make a **`POST`** to **`/botstore/bots`** with a JSON like this:

```javascript
{
"packages": [
"eddi://ai.labs.package/packagestore/packages/<UNIQUE_PACKAGE_ID>?version=<PACKAGE_VERSION>"
],
"channels": []
}
```

### Bot parameters

| Name           | Description                                                                                                                                           |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| packages       | `Array` of `String`, references to `Packages`                                                                                                         |
| channels       | `Array` of `Channel`,                                                                                                                                 |
| Channel.type   | `String`, e.g. `"eddi://ai.labs.channel.facebook"`                                                                                                    |
| Channel.config | `Config` Object. For "Facebook" this object has the params "`appSecret`" (`String`), "`verificationToken`" (`String`), "`pageAccessToken`" (`String`) |

b. You should again get a return code of **`201`** with a `URI` in the `location` header referencing the newly created bot :

`eddi://ai.labs.bot/botstore/bots/`**`<UNIQUE_BOT_ID>`**`?version=`**`<BOT_VERSION>`**

Example:

`eddi://ai.labs.bot/botstore/bots/5a2ae68a17312624f8b8a446?version=1`

> See also the API documentation at [http://localhost:7070/view#!/configurations/createBot](http://localhost:7070/view#!/configurations/createBot)

### 6. Launching the Bot

Finally, we are ready to let the bot fly. From here on, you have the possibility to let an UI do it for you or you do it step by step.

The UI that automates these steps can be reached here: `/chat/unrestricted/`**`<UNIQUE_BOT_ID>`**

Otherwise via REST:

1.  Deploy the Bot:

    Make a **`POST`** to `/administration/unrestricted/deploy/`**`<UNIQUE_BOT_ID>`**`?version=`**`<BOT_VERSION>`**

    You will receive a `202` http code.
2. Since deployment could take a while it has been made **asynchronous**.
3. Make a **`GET`** to `/administration/unrestricted/deploymentstatus/`**`<UNIQUE_BOT_ID>`**`?version=`**`<BOT_VERSION>`** to find out the status of deployment.

**`NOT_FOUND`**, **`IN_PROGRESS`**, **`ERROR` and `READY`** is what you can expect to be returned in the body.

1. As soon as the Bot is deployed and has `READY` status, make a **`POST`** to `/bots/unrestricted/`**`<UNIQUE_BOT_ID>`**
   1. You will receive a `201` with the `URI` for the newly created Conversation, like this:
      1.  e.g.

          `eddi://ai.labs.conversation/conversationstore/conversations/`**`<UNIQUE_CONVERSATION_ID>`**
2. Now it's time to start talking to our Bot 1. Make a **`POST`** to `/bots/unrestricted/`**`<UNIQUE_BOT_ID>`**`/`**`<UNIQUE_CONVERSATION_ID>`**

**Option 1:** is to hand over the input text as `contentType text/plain`. Include the User Input in the body as `text/plain` (e.g. Hello)&#x20;

&#x20;**Option 2:** is to hand over the input as `contentType application/json`, which also allows you to handover context information that you can use with the eddi configurations 1. Include the User Input in the body as application/json (e.g. Hello)

```
{
     "input": "some user input"
}
```

1. You have two query params you can use to config the returned output 1. `returnDetailed` - default is false - will return all sub results of the entire conversation steps, otherwise only public ones such as input, action, output & quickreplies 2. `returnCurrentStepOnly` - default is true - will return only the latest conversation step that has just been processed, otherwise returns all conversation steps since the beginning of this conversation
2. The output from the bot will be returned as JSON
3. If you are interested in fetching the **`conversationmemory`** at any given time, make a **`GET`** to `/bots/unrestricted/`**`<UNIQUE_BOT_ID>`**`/`**`<UNIQUE_CONVERSATION_ID>`**`?returnDetailed=true` (the query param is optional, default is false)

> If you made it till here, CONGRATULATIONS, you have created your first Chatbot with **EDDI** !

By the way you can use the attached **postman collection** below to do all of the steps mentioned above by clicking send on each request in postman.

1. Create dictionary (greetings)
2. Create behaviourSet
3. Create outputSet
4. Creating package
5. Creating bot
6. Deploy the bot
7. Create conversation
8. Say Hello to the bot

{% file src="../.gitbook/assets/Creating and chatting with a bot.postman_collection.json" %}
Example to download
{% endfile %}

### External Links

[Using collections in postman](https://thinkster.io/tutorials/testing-backend-apis-with-postman/using-collections-in-postman)
