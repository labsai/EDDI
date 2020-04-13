# Output Configuration

`Output Configurations` are rather simple as they contain prepared sentences that the Chatbot should reply to the user \(depending on the `actions` coming from the `Behavior Rules`\).

Simple Output Configuration looks like this:

```javascript
{
  "outputSet": [
    {
      "action": "welcome",
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "Welcome! I am E.D.D.I."
          ]
        }
      ]
    }
  ]
}
```

The configuration contains an `array` of `outputSet`, which can contain one or more output objects.

The minimum amount of values that you need to provide in order be functional are **`action`** and **`outputs`.**

Now let's look at a more complex output configuration file:

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
            "I am E.D.D.I. How are you doing today?"
          ]
        }
      ],
      "quickReplies": [
        {
          "value": "I am fine",
          "expressions": "feeling(fine)"
        },
        {
          "value": "not so good",
          "expressions": "feeling(not_good)"
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
            "Hello you! It is a pleasure meeting you.. :-)"
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
            "Did we already say hi ?! Well, twice is better than not at all! ;-)",
            "I like it if people are polite and greet twice, rather than not at all ;-)"
          ]
        }
      ]
    },
    {
      "action": "say_goodbye",
      "outputs": [
        {
          "type": "text",
          "valueAlternatives": [
            "See you soon!"
          ]
        }
      ]
    }
  ]
}
```

### Explanation of model

| Key | Description |
| :--- | :--- |
| action | This will be the "`actions`" coming from the `Behavior Rules`. If a rule succeeds, the defined action will be stored in the **conversation memory.** |
| outputs | This array of output objects are the outputs that will be replied back to the user in case the `action` matched the `action key`. You can define multiple `output objects`, which represent separate chat bubbles on the client side. If more than one `valueAlternatives` is defined, one will be picked randomly. If this `output` will be triggered again in a future `conversationStep`, then another `output` of this array will be favored in order to avoid repetition. \(If all available `outputs` have been selected, it is randomized again like in the beginning\). The `type` is mainly there to be able to distinguish the type of output on the client side \(e.g. `image`, `video`, `audio`, etc\). |
| quickReplies | This is an `array` of `QuickReply objects`. Each `object` must contain a value, which is the text that should be displayed to the user \(e.g. as button\) in the conversation flow. The `expressions` is `optional`, you can define one or more comma separated expressions that define the meaning of this `QuickReply`. Those `expressions` will be temporarily taken into account in the `semantic parseri` in the next `conversationStep`. So if a user chooses one of the quick replies, the parser would recognize them \(even if not defined in any of the `dictionaries` explicitly\) and resolve them with the `expressions` defined within this quick reply. |
| timesOccurred | How often this `action` should have occurred within that `conversation` in order to be selected as `output` to the user \(thus, if `value` of `1`, it would be chosen if the action occurs for the second time\) |

