import { compileJsonSchema, IJsonError } from './JsonHelpers';
import * as _ from 'lodash';
import { JSONSchema4 } from 'json-schema';

export const isJsonString = (text: string) => {
  try {
    JSON.parse(text);
  } catch (e) {
    return false;
  }
  return true;
};

const validateJson = (schema: JSONSchema4, text: string) => {
  let errors: IJsonError[] = [];
  if (!isJsonString(text)) {
    const jsonParseError: IJsonError = {
      message: 'Error parsing JSON.',
      line: 0,
    };
    errors.push(jsonParseError);
  } else {
    errors = compileJsonSchema(schema, text);
  }
  const isValidJson = _.isEmpty(errors);
  return isValidJson;
};

export default validateJson;
