import axios from 'axios';
import {
  BEHAVIOR,
  BEHAVIOR_PATH,
  BOT,
  BOT_PATH,
  PROPERTYSETTER,
  PROPERTYSETTER_PATH,
  HTTPCALLS,
  HTTPCALLS_PATH,
  OUTPUT,
  OUTPUT_PATH,
  PACKAGE,
  PACKAGE_PATH,
  REGULAR_DICTIONARY,
  REGULAR_DICTIONARY_PATH,
  GITCALLS_PATH,
  GITCALLS,
} from './EddiTypes';
import * as _ from 'lodash';

import appConfig from '../../../_/env.json';

let apiUrlQuery: string;

export function setApiUrlQuery(url: string) {
  apiUrlQuery = url;
}

export function getApiUrlQuery(): string {
  return apiUrlQuery;
}

export async function getAPIUrl(): Promise<string> {
  if (!_.isEmpty(apiUrlQuery)) {
    return apiUrlQuery;
  }
  const apiUrl = appConfig.eddiApiUrl;

  if (apiUrl) {
    return apiUrl;
  } else {
    console.error(`Failed to get API url. `);
    throw new Error();
  }
}

export async function getAuthMethod(): Promise<string> {
  const authMethod = appConfig.authMethod;

  if (authMethod) {
    return authMethod;
  } else {
    console.error(`Failed to get authMethod. `);
    throw new Error();
  }
}

export async function getAuthRealm(): Promise<string> {
  const authRealm = appConfig.authRealm;

  if (authRealm) {
    return authRealm;
  } else {
    console.error(`Failed to get authRealm. `);
    throw new Error();
  }
}

export async function getAuthUrl(): Promise<string> {
  const authUrl = appConfig.authUrl;

  if (authUrl) {
    return authUrl;
  } else {
    console.error(`Failed to get authUrl. `);
    throw new Error();
  }
}

export async function getAuthClientId(): Promise<string> {
  const authClientId = appConfig.authClientId;

  if (authClientId) {
    return authClientId;
  } else {
    console.error(`Failed to get authClientId. `);
    throw new Error();
  }
}

let readOnlyQuery: string;

export function setReadOnlyQuery(urlQuery: string) {
  readOnlyQuery = urlQuery;
}

export function getReadOnlyQuery(): string {
  return readOnlyQuery;
}

export async function getReadOnly(): Promise<boolean> {
  if (!_.isEmpty(readOnlyQuery)) {
    return readOnlyQuery === 'true';
  }
  const readOnlyDomain = appConfig.readOnlyDomain;
  const apiUrl = await getAPIUrl();

  if (readOnlyDomain) {
    return readOnlyDomain?.includes(apiUrl);
  } else {
    console.error(`Failed to get readOnlyDomain. `);
    throw new Error();
  }
}

export function getTypeFromResource(resource: string): string {
  if (resource?.includes(REGULAR_DICTIONARY_PATH)) {
    return REGULAR_DICTIONARY;
  } else if (resource?.includes(BEHAVIOR_PATH)) {
    return BEHAVIOR;
  } else if (resource?.includes(OUTPUT_PATH)) {
    return OUTPUT;
  } else if (resource?.includes(HTTPCALLS_PATH)) {
    return HTTPCALLS;
  } else if (resource?.includes(GITCALLS_PATH)) {
    return GITCALLS;
  } else if (resource?.includes(PROPERTYSETTER_PATH)) {
    return PROPERTYSETTER;
  } else if (resource?.includes(BOT_PATH)) {
    return BOT;
  } else if (resource?.includes(PACKAGE_PATH)) {
    return PACKAGE;
  }
}

export function getTypePath(type: string): string {
  switch (type) {
    case REGULAR_DICTIONARY:
      return REGULAR_DICTIONARY_PATH;
    case BEHAVIOR:
      return BEHAVIOR_PATH;
    case OUTPUT:
      return OUTPUT_PATH;
    case HTTPCALLS:
      return HTTPCALLS_PATH;
    case GITCALLS:
      return GITCALLS_PATH;
    case PROPERTYSETTER:
      return PROPERTYSETTER_PATH;
    case BOT:
      return BOT_PATH;
    case PACKAGE:
      return PACKAGE_PATH;
    default:
      return null;
  }
}

export const DEFAULT_LIMIT: number = 10;
