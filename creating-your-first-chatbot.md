# Creating your first Chatbot

## Creating your first Chatbot

_Prerequisites_

_Up and Running instance of **EDDI** \(See:_ [Getting started](getting-started.md) _\)_

## Introduction

In order to build a Chatbot with **EDDI**, you will have to create a few configuration files and `POST` them to the corresponding REST APIs.

![](.gitbook/assets/create-bot%20%281%29.png)

A chatbot consists of the following elements:

1. \(Regular-\) **`Dictionary`** to define the inputs from the users as well as its meanings in respective categories \(called **`Expressions`** in **EDDI**\).
2. **`Behavior Rules`** creating **Actions** based on decision making with predefined as well as custom conditions.
3. **`Output`** to answer the users request based on the results from the behavior rule execution.
4. **`Package`** to align the **\`LifecycleTasks**\` \(such as the parser, behavior evaluation, output generation, ...\)
5. **`Bot`** to align different Packages and Channels.

[Structure of Identifiers](https://www.notion.so/f084453cde86426da610831b19f752ab)

Follow these steps to create the configuration files you will need:

### **1. Creating a Regular Dictionary**

> See also [Semantic Parser](semantic-parser.md)

Create regular dictionaries in order to store custom words and phrases. A dictionary is there to map user input to expressions, which are later used in `Behavior Rules`. a. **`POST`** to **`/regulardictionarystore/regulardictionaries`** with a JSON in the body like this:

```javascript
{
  "language": "en",
  "words": [
    {
      "word": "hello",
      "exp": "greeting(hello)",
      "frequency": 0
    },
    {
      "word": "hi",
      "exp": "greeting(hi)",
      "frequency": 0
    },
    {
      "word": "bye",
      "exp": "goodbye(bye)",
      "frequency": 0
    },
    {
      "word": "thanks",
      "exp": "thanks(thanks)",
      "frequency": 0
    }
  ],
  "phrases": [
    {
      "phrase": "good afternoon",
      "exp": "greeting(good_afternoon)"
    },
    {
      "phrase": "how are you",
      "exp": "how_are_you"
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
"exp" : "greeting(hello)", \
"frequency" : 0 \
}, \
{ \
"word" : "hi", \
"exp" : "greeting(hi)", \
"frequency" : 0 \
}, \
{ \
"word" : "bye", \
"exp" : "goodbye(bye)", \
"frequency" : 0 \
}, \
{ \
"word" : "thanks", \
"exp" : "thanks(thanks)", \
"frequency" : 0 \
} \
], \
"phrases" : [ \
{ \
"phrase" : "good afternoon", \
"exp" : "greeting(good_afternoon)" \
}, \
{ \
"phrase" : "how are you", \
"exp" : "how_are_you" \
} \
] \
}' 'http://localhost:7070/regulardictionarystore/regulardictionaries'
```

### Dictionary parameters

| Name | Description | Required |
| :--- | :--- | :--- |
| words | `Array` of `Word` |  |
| phrases | `Array` of `Phrase` |  |
| Word.word | `String`,Single word, no spaces. | True |
| Word.exp | `String`,"greeting\(hello\)": "greeting" is the category of this expression and "hello" is an entity. |  |
| Word.frequency | `int`, Used for a randomizer |  |
| Phrase.phrase | `String`, Spaces allowed | True |
| Phrase.exp | `String`, "greeting\(hello\)": "greeting" is the category of this expression and "hello" is an entity. |  |

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
                "maxTimesOccurred": 0,
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

<table>
  <thead>
    <tr>
      <th style="text-align:left">Name</th>
      <th style="text-align:left">Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left">BehaviorRule.name</td>
      <td style="text-align:left"><code>String</code>, e.g. &quot;Smalltalk&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviourGroup.behaviorRules</td>
      <td style="text-align:left"><code>Array </code>of <code>BehaviorRule</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviorRule.name</td>
      <td style="text-align:left"><code>String</code>, e.g. &quot;Greeting&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviorRule.actions</td>
      <td style="text-align:left"><code>Array </code>of <code>String</code>, e.g. &quot;greet&quot; or &quot;CONVERSATION_END&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviorRule.children</td>
      <td style="text-align:left"><code>Array </code>of <code>RuleChild</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">RuleChild.type</td>
      <td style="text-align:left">
        <p><code>String</code>, allowed values:</p>
        <p>&#x2014;&gt;&quot;<code>inputmatcher</code>&quot; (has params: &quot;<code>expressions</code>&quot;
          (<code>Array </code>of <code>String</code>( and &quot;<code>occurrence</code>&quot;)</p>
        <p>&#x2014;&gt;&quot;<code>negation</code>&quot; (<code>BehaviorExtension </code>object,
          has params: &quot;<code>children</code>&quot; and &quot;<code>occurrence</code>&quot;)</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">RuleChild.values</td>
      <td style="text-align:left">
        <p><code>HashMap</code>, allowed values:</p>
        <p>&#x2014;&gt;&quot;<code>expressions</code>&quot;: <code>String</code>,
          mandatory. Expression e.g. &quot;greeting(*)&quot; or &quot;how_are_you&quot;</p>
        <p>&#x2014;&gt;&quot;<code>occurrence</code>&quot;: <code>String</code>, optional.
          Allowed values &quot;<code>currentStep</code>&quot;</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Negation.children</td>
      <td style="text-align:left"><code>Array </code>of <code>NegationChild</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">NegationChild.type</td>
      <td style="text-align:left"><code>String </code>e.g. &quot;<code>occurrence</code>&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">NegationChild.values</td>
      <td style="text-align:left">
        <p>HashMap, allowed values:</p>
        <p>&#x2014;&gt;&quot;<code>maxTimesOccurred</code>&quot;: <code>Integer</code>,
          e.g. 1</p>
        <p>&#x2014;&gt;&quot;<code>minTimesOccurred</code>&quot;: <code>Integer</code>,
          e.g. 1</p>
        <p>&#x2014;&gt;&quot;<code>behaviorRuleName</code>&quot;: <code>String</code>
        </p>
      </td>
    </tr>
  </tbody>
</table>You should again get a return code of **`201`** with a **`URI`** in the **`location` header** referencing the newly created `Behavior Rules`:

`eddi://ai.labs.behavior/behaviorstore/behaviorsets/`**`<UNIQUE_BEHAVIOR_ID>`**`?version=`**`<BEHAVIOR_VERSION>`**

Example:

`eddi://ai.labs.behavior/behaviorstore/behaviorsets/5a26d8fd17312628b46119fb?version=1`

### 3. Creating Output

> [See also Output Configuration.](output-configuration.md)

You have guessed it correctly, another **`POST`** to **`/outputstore/outputsets`** create the bot's `Output` with a JSON in the body like this:

```javascript
{
  "outputSet": [
    {
      "action": "welcome",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Welcome!"
          ]
        },
        {
          "type": "text",
          "valueAlternatives": [
            "My name is E.D.D.I"
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
          "type": "text",
          "valueAlternatives": [
            "Hi there! Nice to meet up! :-)",
            "Hey you!"
          ]
        }
      ]
    },
    {
      "action": "greet",
      "timesOccurred": 1,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Did we already say hi ?! Well, twice is better than not at all! ;-)"
          ]
        }
      ]
    },
    {
      "action": "say_goodbye",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "See you soon!"
          ]
        }
      ]
    },
    {
      "action": "thank",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Your Welcome!"
          ]
        }
      ]
    },
    {
      "action": "how_are_you",
      "timesOccurred": 0,
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Pretty good.. having lovely conversations all day long.. :-D"
          ]
        }
      ]
    }
  ]
}
```

### Output parameters

<table>
  <thead>
    <tr>
      <th style="text-align:left">Name</th>
      <th style="text-align:left">Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left">BehaviorRule.name</td>
      <td style="text-align:left"><code>String</code>, e.g. &quot;Smalltalk&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviourGroup.behaviorRules</td>
      <td style="text-align:left"><code>Array </code>of <code>BehaviorRule</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviorRule.name</td>
      <td style="text-align:left"><code>String</code>, e.g. &quot;Greeting&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviorRule.actions</td>
      <td style="text-align:left"><code>Array </code>of <code>String</code>, e.g. &quot;greet&quot; or &quot;CONVERSATION_END&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">BehaviorRule.children</td>
      <td style="text-align:left"><code>Array </code>of <code>RuleChild</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">RuleChild.type</td>
      <td style="text-align:left">
        <p><code>String</code>, allowed values:</p>
        <p>&#x2014;&gt;&quot;<code>inputmatcher</code>&quot; (has params: &quot;<code>expressions</code>&quot;
          (<code>Array </code>of <code>String</code>( and &quot;<code>occurrence</code>&quot;)</p>
        <p>&#x2014;&gt;&quot;<code>negation</code>&quot; (<code>BehaviorExtension </code>object,
          has params: &quot;<code>children</code>&quot; and &quot;<code>occurrence</code>&quot;)</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">RuleChild.values</td>
      <td style="text-align:left">
        <p><code>HashMap</code>, allowed values:</p>
        <p>&#x2014;&gt;&quot;<code>expressions</code>&quot;: <code>String</code>,
          mandatory. Expression e.g. &quot;greeting(*)&quot; or &quot;how_are_you&quot;</p>
        <p>&#x2014;&gt;&quot;<code>occurrence</code>&quot;: <code>String</code>, optional.
          Allowed values &quot;<code>currentStep</code>&quot;</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">Negation.children</td>
      <td style="text-align:left"><code>Array </code>of <code>NegationChild</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">NegationChild.type</td>
      <td style="text-align:left"><code>String </code>e.g. &quot;<code>occurrence</code>&quot;</td>
    </tr>
    <tr>
      <td style="text-align:left">NegationChild.values</td>
      <td style="text-align:left">
        <p>HashMap, allowed values:</p>
        <p>&#x2014;&gt;&quot;<code>maxTimesOccurred</code>&quot;: <code>Integer</code>,
          e.g. 1</p>
        <p>&#x2014;&gt;&quot;<code>minTimesOccurred</code>&quot;: <code>Integer</code>,
          e.g. 1</p>
        <p>&#x2014;&gt;&quot;<code>behaviorRuleName</code>&quot;: <code>String</code>
        </p>
      </td>
    </tr>
  </tbody>
</table>You should again get a return code of **`201`** with a **`URI`** in the **`location` header** referencing the newly created output :

`eddi://ai.labs.output/outputstore/outputsets/`**`<UNIQUE_OUTPUTSET_ID>`**`?version=`**`<OUTPUTSET_VERSION>`**

Example :

`eddi://ai.labs.output/outputstore/outputsets/5a26d97417312628b46119fc?version=1`

### 4. Creating the Package

Now we will align the just created `LifecycleTasks` in the `Package`. Make a **`POST`** to **`/packagestore/packages`** with a JSON in the body like this:

```javascript
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.normalizer",
      "config": {
        "allowedChars": "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ !?:;.,",
        "convertUmlaute": "true"
      }
    },
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

| Name | Description | Required |
| :--- | :--- | :--- |
| packageextensions | `Array` of `PackageExtension` |  |
| PackageExtension.type | possible values, see table below "`Extension Types`" |  |
| PackageExtension.extensions | `Array` of `Object` | False |
| PackageExtension.config | `Config` object, but can be empty. | True |

Extension Types

<table>
  <thead>
    <tr>
      <th style="text-align:left">Extension</th>
      <th style="text-align:left">Config</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align:left">eddi://ai.labs.behavior</td>
      <td style="text-align:left">Object <code>Config </code>contains param <code>uri </code>with Link to
        a behavior set, e.g. <code>eddi://ai.labs.behavior/behaviorstore/behaviorsets/5a26d8fd17312628b46119fb?version=1</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">eddi://ai.labs.normalizer</td>
      <td style="text-align:left">
        <p>To normalize the text, e.g. throw away not allowed characters or convert
          umlauts. Object &quot;<code>config</code>&quot; has the following parameters:</p>
        <p>&#x2014;&gt;&quot;<code>allowedChars</code>&quot;: String, e.g.<code> </code>&quot;1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ
          !?:;.,&quot;,</p>
        <p>&#x2014;&gt;&quot;<code>convertUmlaute</code>&quot;: Boolean, e.g. &quot;true&quot;</p>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">eddi://ai.labs.output</td>
      <td style="text-align:left">Object Config contains param uri with Link to output set, e.g. <code>eddi://ai.labs.output/outputstore/outputsets/5a26d97417312628b46119fc?version=1</code>
      </td>
    </tr>
    <tr>
      <td style="text-align:left">eddi://ai.labs.parser</td>
      <td style="text-align:left">
        <p><code>Dictionaries </code>and/or corrections</p>
        <p>Object &quot;<code>extensions</code>&quot; can contain &quot;<code>dictionaries</code>&quot;
          (<code>Array </code>of <code>Dictionary</code>) and/or &quot;<code>corrections</code>&quot;
          (<code>Array </code>of <code>Correction</code>)</p>
        <p>Object &quot;<code>Dictionary</code>&quot; has params &quot;<code>type</code>&quot;
          and &quot;<code>config</code>&quot; (optional)</p>
        <p><code>Dictionary.type</code> can reference <code>Regular-Dictionaries</code> &quot;<code>eddi://ai.labs.parser.dictionaries.regular</code>&quot;
          (needs param &quot;<code>config.uri</code>&quot;) or be one of the <b>EDDI</b> out
          of the box types:</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.dictionaries.integer</code>&quot;</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.dictionaries.decimal</code>&quot;</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.dictionaries.punctuation</code>&quot;</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.dictionaries.email</code>&quot;</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.dictionaries.time</code>&quot;</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.dictionaries.ordinalNumber</code>&quot;</p>
        <p>Object &quot;<code>Correction</code>&quot; has params &quot;<code>type</code>&quot;
          and &quot;<code>config</code>&quot; (optional)</p>
        <p><code>Correction.type</code> can reference one of the EDDI out of the box
          types:</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.corrections.stemming</code>&quot;:
          Object &quot;<code>config</code>&quot; has params &quot;<code>language</code>&quot;
          (<code>String </code>e.g. &quot;english&quot;) and &quot;<code>lookupIfKnown</code>&quot;
          (<code>Boolean</code>)</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.corrections.levenshtein</code>&quot;:
          Object &quot;<code>config</code>&quot; has param &quot;<code>distance</code>&quot;
          (Integer, e.g. 2)</p>
        <p>&#x2014;&gt;&quot;<code>eddi://ai.labs.parser.corrections.mergedTerms</code>&quot;</p>
      </td>
    </tr>
  </tbody>
</table>> New

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

| Name | Description |
| :--- | :--- |
| setOnActions.actions | \(`string`\) defines which for which actions \(triggered by BehaviorRules\) these Properties should be set |
| setOnActions.setProperties | \(`Array` &lt;`Property`&gt;: \) must respect the `Property`model: `name`, `fromObjectPath` and `scope`. |
| setOnActions.setProperties.name | \(`string`\) name of the `Property`. |
| setOnActions.setProperties.fromObjectPath | \(`string`\) path to the json object. |
| setOnActions.setProperties.scope | \(`string`\) Possible values `step`, `conversation` and `longTerm` . |

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

> See also the API documentation at [http://localhost:7070/view\#!/configurations/createPackage](http://localhost:7070/view#!/configurations/createPackage)

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

| Name | Description |
| :--- | :--- |
| packages | `Array` of `String`, references to `Packages` |
| channels | `Array` of `Channel`, |
| Channel.type | `String`, e.g. `"eddi://ai.labs.channel.facebook"` |
| Channel.config | `Config` Object. For "Facebook" this object has the params "`appSecret`" \(`String`\), "`verificationToken`" \(`String`\), "`pageAccessToken`" \(`String`\) |

b. You should again get a return code of **`201`** with a `URI` in the `location` header referencing the newly created bot :

`eddi://ai.labs.bot/botstore/bots/`**`<UNIQUE_BOT_ID>`**`?version=`**`<BOT_VERSION>`**

Example:

`eddi://ai.labs.bot/botstore/bots/5a2ae68a17312624f8b8a446?version=1`

> See also the API documentation at [http://localhost:7070/view\#!/configurations/createBot](http://localhost:7070/view#!/configurations/createBot)

### 6. Launching the Bot

Finally, we are ready to let the bot fly. From here on, you have the possibility to let an UI do it for you or you do it step by step.

The UI that automates these steps can be reached here: `/chat/unrestricted/`**`<UNIQUE_BOT_ID>`**

Otherwise via REST:

1. Deploy the Bot:

   Make a **`POST`** to `/administration/unrestricted/deploy/`**`<UNIQUE_BOT_ID>`**`?version=`**`<BOT_VERSION>`**

   You will receive a `202` http code.

2. Since deployment could take a while it has been made **asynchronous**.
3. Make a **`GET`** to `/administration/unrestricted/deploymentstatus/`**`<UNIQUE_BOT_ID>`**`?version=`**`<BOT_VERSION>`** to find out the status of deployement.

**`NOT_FOUND`**, **`IN_PROGRESS`**, **`ERROR` and \`READY**\` is what you can expect to be returned in the body.

1. As soon as the Bot is deployed and has `READY` status, make a **`POST`** to `/bots/unrestricted/`**`<UNIQUE_BOT_ID>`**
   1. You will receive a `201` with the `URI` for the newly created Conversation, like this:
      1. e.g.

         `eddi://ai.labs.conversation/conversationstore/conversations/`**`<UNIQUE_CONVERSATION_ID>`**
2. Now it's time to start talking to our Bot 1. Make a **`POST`** to `/bots/unrestricted/`**`<UNIQUE_BOT_ID>`**`/`**`<UNIQUE_CONVERSATION_ID>`** 1. **Option 1:** is to hand over the input text as `contentType text/plain` 1. Include the User Input in the body as `text/plain` \(e.g. Hello\) 2. **Option 2:** is to hand over the input as `contentType application/json`, which also allows you to handover context information that you can use with the eddi configurations 1. Include the User Input in the body as application/json \(e.g. Hello\)

   ```text
   {
        "input": "some user input"
   }
   ```

3. you have two query params you can use to config the returned output 1. `returnDetailed` - default is false - will return all sub results of the entire conversation steps, otherwise only public ones such as input, action, output & quickreplies 2. `returnCurrentStepOnly` - default is true - will return only the latest conversation step that has just been processed, otherwise returns all conversation steps since the beginning of this conversation
4. The output from the bot will be returned as JSON
5. If you are interested in fetching the **`conversationmemory`** at any given time, make a **`GET`** to `/bots/unrestricted/`**`<UNIQUE_BOT_ID>`**`/`**`<UNIQUE_CONVERSATION_ID>`**`?returnDetailed=true` \(the query param is optional, default is false\)

> If you made it till here, CONGRATULATIONS, you have created your first Chatbot with **EDDI** !

By the way you can use the attached **postman collection** below to do all of the steps mentioned above by clicking send on each request in postman.

1. Create dictionary \(greetings\)
2. Create behaviourSet
3. Create outputSet
4. Creating package
5. Creating bot
6. Deploy the bot
7. Create conversation
8. Say Hello to the bot

{% file src=".gitbook/assets/creating-and-chatting-with-a-bot.postman\_collection.json" caption="Example to download" %}

### Additional info :

[Using collections in postman](https://thinkster.io/tutorials/testing-backend-apis-with-postman/using-collections-in-postman)

