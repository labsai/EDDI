# FAQs

## How to...?

## ...start a conversation with a welcome / intro message?

You will need `behavior rules` and an `outputset` for that.&#x20;

For the behavior rules, you have three possibilities (ordered by recommendation):

### 1) Match for the action `CONVERSATION_START`

```javascript
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Welcome",
          "actions": [
            "welcome"
          ],
          "conditions": [
            {
              "type": "actionmatcher",
              "configs": {
                "actions": "CONVERSATION_START"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### 2) check if the triggered action has never be triggered before

```javascript
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Welcome",
          "actions": [
            "welcome"
          ],
          "conditions": [
            {
              "type": "actionmatcher",
              "configs": {
                "actions": "welcome",
                "occurrence": "never"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

### 3) Check how often this rule has succeeded before.&#x20;

```javascript
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
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
        }
      ]
    }
  ]
}
```

### Outputset:

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
              "text": "Some output here...",
              "delay": 3000
            }
          ]
        }
      ],
      "quickReplies": [
        // quickreplies here
      ]
    }
  ]
}
```

## ...say something based on what the bot previously said?&#x20;

(Think of a form-like behavior, asking a couple of questions and sending these results somewhere.)

### Check whether a certain `action` had been triggered in the previous conversation step.

```javascript
{
  "behaviorGroups": [
    {
      "name": "Onboarding",
      "behaviorRules": [
        {
          "name": "Ask for Name",
          "actions": [
            "ask_for_name"
          ],
          "conditions": [
            {
              "type": "actionmatcher",
              "configs": {
                "actions": "some_previous_action",
                "occurrence": "lastStep"
              }
            }
          ]
        }
      ]
    }
  ]
}
```



Have a question that is not covered? Drop us an email at contact@labs.ai, we are happy to enhance our documentation!
