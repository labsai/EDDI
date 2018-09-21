import { getAPIUrl } from '../ApiFunctions';
import Parser from '../Parser';
import axios from 'axios';
import {
  IDescriptorResponse,
  IDetailedDescriptor,
  IResponse,
} from '../AxiosFunctions';

export async function postJsonHelper(
  url: string,
  payload: object,
): Promise<IResponse> {
  console.log(`${await getAPIUrl()}${url}`);
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
