# Creating your first Chatbots

_Prerequisites: Up and Running instance of **EDDI** (see:_ [_Getting started_](../getting-started.md)_)_

## How does it work?

In order to build a Chatbot with **EDDI**, you will have to create a few configuration files and `POST` them to the corresponding REST APIs.

![](<../.gitbook/assets/eddi-tech-overview-2 (2).jpg>)

A chatbot can consists of the following elements:

1. (Regular) **`Dictionary`** to define the inputs from the users as well as their meanings in respective categories, expressed by a expression language `e.g. apple -> fruit(apple)`
2. **`Behavior Rules`** triggering **actions** based on execution of behavior rules checking on certain conditions within the current conversation
3. **`Http Connector`** requests/sends data to a Rest API and makes the json response available within the conversation (e.g for Output**`)`**
4. **`Output`** to answer the user's request based on **actions** triggered by behavior rules
5. **`Package`** to define which **\`LifecycleTasks**\` (such as the parser, behavior rules, rest api connector, output generation, ...) should be executed in order by how they are defined
6. **`Bot`** to define which packages should be executed in this bot



### Example of a resource reference

`eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/ID?version=VERSION`

`eddi://`   URI resources starting with this protocol are to be related with in EDDI&#x20;

`ai.labs.regulardictionary` Type of resource

`/regulardictionarystore/regulardictionaries` API path

&#x20;`ID` ID of the resources

`VERSION`  Read-only version of the resource (each change is a new version)

&#x20;Version of this resource (each update operation will create a new version of the resource)

