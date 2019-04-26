import { getAPIUrl } from '../ApiFunctions';
import Parser from '../Parser';
import axios from 'axios';
import {
  IDescriptorResponse,
  IDetailedDescriptor,
  IResponse,
} from '../AxiosFunctions';
import { DICTIONARY_SCHEMA } from '../JsonSchemas/JsonSchemas';
import * as Ajv from 'ajv';
import * as Jsm from 'json-source-map';
import * as _ from 'lodash';

export async function postJsonHelper(
  url: string,
  payload: object,
): Promise<IResponse> {
  const res = await axios.post(
    `${await getAPIUrl()}${url}`,
    JSON.stringify(payload),
    {
      headers: { 'Content-Type': 'application/json' },
    },
  );
  return res;
}

export async function putHelper(
  resource: string,
  url: string,
  payload: object,
) {
  await axios.put(
    `${await getAPIUrl()}${url}${Parser.getIdAndVersion(resource)}`,
    JSON.stringify(payload),
    {
      headers: { 'Content-Type': 'application/json' },
    },
  );
}

export function mapDataToDetailedDescriptors(
  data: IDescriptorResponse,
): IDetailedDescriptor[] {
  const detailedDescriptors: IDetailedDescriptor[] = data.data.map(pkg => {
    const version = Parser.getVersion(pkg.resource);
    return {
      id: Parser.getId(pkg.resource),
      version,
      currentVersion: version,
      createdOn: pkg.createdOn,
      description: pkg.description,
      lastModifiedOn: pkg.lastModifiedOn,
      name: pkg.name,
      resource: pkg.resource,
    };
  });
  return detailedDescriptors;
}

export interface IJsonError {
  message: string;
  line: number;
}

function formatKeyPath(path: string): string {
  let key = path
    .split('.')
    .join('/')
    .split('[')
    .join('/')
    .split(']')
    .join('');
  console.log(key);
  return key;
}

export function compileJsonSchema(schema: {}, jsonText: string): IJsonError[] {
  const json = Jsm.parse(jsonText);
  const ajv = new Ajv({ allErrors: true });
  const validate = ajv.compile(schema);
  console.log(validate);
  console.log(validate.errors);
  const validJson = validate(json.data);
  console.log(validJson);
  console.log(validate.errors);
  console.log(json.pointers);
  if (_.isEmpty(validate.errors)) {
    return [];
  }
  const errors: IJsonError[] = validate.errors.map(err => {
    return {
      message: err.message,
      line: json.pointers[formatKeyPath(err.dataPath)].value.line,
    };
  });
  console.log(errors);
  return errors;
}
