export const DICTIONARY_SCHEMA = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://eddi.labs.ai/dictionary.json',
  type: 'object',
  title: 'Dictionary Schema',
  required: ['language'],
  anyOf: [
    {
      required: ['words'],
    },
    {
      required: ['phrases'],
    },
  ],
  properties: {
    language: {
      type: 'string',
      default: 'en',
      pattern: '^(.*)$',
    },
    words: {
      type: 'array',
      items: {
        type: 'object',
        required: ['word', 'exp', 'frequency'],
        properties: {
          word: {
            type: 'string',
            pattern: '^([^ ]{2,})$',
          },
          exp: {
            type: 'string',
            pattern: '^[^ ]{2,}(\\([^ ]{2,}\\))$',
          },
          frequency: {
            type: 'integer',
            default: 0,
          },
        },
      },
    },
    phrases: {
      type: 'array',
      items: {
        type: 'object',
        required: ['phrase', 'exp'],
        properties: {
          phrase: {
            type: 'string',
            pattern: '^[a-zA-Z0-9]+( [a-zA-Z0-9]+){1,}$',
          },
          exp: {
            type: 'string',
            pattern: '^[^ ]{2,}(\\([^ ]{2,}\\))$',
          },
        },
      },
    },
  },
};
