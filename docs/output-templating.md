# Output Templating

One of the coolest features of **EDDI** is it will allow you dynamically template your output based on data that you would receive from `httpCalls` or `context information` for instance, that makes **EDDI's** replies to user interactions richly and dynamically.

The **output templating** is evaluated by `thymeleaf` **templating engine**, that means you can use the majority of `thymeleaf` `tags` and `expression language` to define how you would like your output to be.

## Enabling the feature:

Basically while creating the bot you must include `eddi://ai.labs.output` to one of the `packages` that will be part of the bot.

> **Important:** The templating feature will not work if it is included before `eddi://ai.labs.output` extension, **it must be included after**.

## Example

Here is how the output templating should be specified **inside of a package.**&#x20;

```javascript
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.output",
      "config": {
        "uri": "eddi://ai.labs.output/outputstore/outputsets/{{outputset_id}}?version=1"
      }
    },
    {
      "type": "eddi://ai.labs.templating"
    }
  ]
}
```

Make sure the templating is defined after the output, not before.

## _**Additional Information :**_

[Thymeleaf documentation.](https://www.thymeleaf.org/)
