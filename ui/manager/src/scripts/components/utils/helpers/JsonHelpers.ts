import * as Ajv from 'ajv';
import * as Jsm from 'json-source-map';
import * as _ from 'lodash';
import { getAPIUrl } from '../ApiFunctions';
import { IResponse } from '../AxiosFunctions';
import axios from '../axiosInterseptor';
import {
  BEHAVIOR,
  BOT,
  HTTPCALLS,
  OUTPUT,
  PACKAGE,
  PROPERTYSETTER,
  REGULAR_DICTIONARY,
} from '../EddiTypes';
import Parser from '../Parser';
import * as Snippets from './Snippets';
import { ISnippet } from './Snippets';

const metaSchema = require('ajv/lib/refs/json-schema-draft-04.json');

export async function postJsonHelper(
  url: string,
  payload = {},
): Promise<IResponse> {
  const res = await axios.post(`${await getAPIUrl()}${url}`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  return res;
}

export async function putHelper(
  resource: string,
  url: string,
  payload: string,
) {
  await axios.put(
    `${await getAPIUrl()}${url}${Parser.getIdAndVersion(resource)}`,
    payload,
    {
      headers: { 'Content-Type': 'application/json' },
    },
  );
}

export interface IJsonError {
  message: string;
  line: number;
}

function formatKeyPath(path: string): string {
  const key = path.replace(/\.|\[/g, '/').replace(/]|'/g, '');
  return key;
}

export function compileJsonSchema(schema: {}, jsonText: string): IJsonError[] {
  const json = Jsm.parse(jsonText);
  const ajv = new Ajv({ schemaId: 'id', allErrors: true });
  ajv.addMetaSchema(metaSchema);
  const validate = ajv.compile(schema);
  validate(json.data);
  if (_.isEmpty(validate.errors)) {
    return [];
  }
  const errors: IJsonError[] = validate.errors.map((err) => {
    return {
      message: err.message,
      line: json.pointers[formatKeyPath(err.dataPath)].value.line,
    };
  });
  return errors;
}

export function getSnippets(type: string): ISnippet[] {
  switch (type) {
    case REGULAR_DICTIONARY:
      return Snippets.regularDictionarySnippets;
    case BEHAVIOR:
      return Snippets.behaviorSnippets;
    case OUTPUT:
      return Snippets.outputSnippets;
    case HTTPCALLS:
      return Snippets.httpCallsSnippets;
    case PROPERTYSETTER:
      return Snippets.propertySetterSnippets;
    case PACKAGE:
      return Snippets.packageSnippets;
    case BOT:
      return Snippets.botSnippets;
    default:
      return [];
  }
}
