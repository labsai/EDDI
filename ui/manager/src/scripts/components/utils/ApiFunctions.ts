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

const envUrl = '/_/env.json';
let apiUrlPromise: Promise<string>;
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
  if (!apiUrlPromise) {
    return (apiUrlPromise = axios
      .get(envUrl)
      .then((overrides) => {
        if (overrides.data.EDDI_API_URL) {
          let apiUrl = overrides.data.EDDI_API_URL;
          if (apiUrl[apiUrl.length - 1] === '/') {
            apiUrl = apiUrl.substring(0, apiUrl.length - 1);
          }
          return apiUrl;
        } else {
          if (process.env.eddiApiUrl) {
            return process.env.eddiApiUrl;
          } else {
            throw new Error('No API url defined');
          }
        }
      })
      .catch((err) => {
        if (process.env.environment === 'local') {
          console.log('Running locally. using local config for API url');
          return (apiUrlPromise = Promise.resolve(process.env.eddiApiUrl));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
          apiUrlPromise = null;
          throw err;
        }
      }));
  } else {
    let apiUrl = await apiUrlPromise;
    if (apiUrl[apiUrl.length - 1] === '/') {
      apiUrl = apiUrl.substring(0, apiUrl.length - 1);
    }
    return apiUrl;
  }
}

let authMethodPromise: Promise<string>;

export async function getAuthMethod(): Promise<string> {
  if (!authMethodPromise) {
    return (authMethodPromise = axios
      .get(envUrl)
      .then((overrides) => {
        if (overrides.data.AUTH_METHOD) {
          let authMethod = overrides.data.AUTH_METHOD;
          return authMethod;
        } else {
          if (process.env.authMethod) {
            return process.env.authMethod;
          } else {
            throw new Error('No authMethod defined');
          }
        }
      })
      .catch((err) => {
        if (process.env.environment === 'local') {
          return (authMethodPromise = Promise.resolve(process.env.authMethod));
        } else {
          console.error(`Failed to get authMethod. Error: ${err.message}`);
          authMethodPromise = null;
          throw err;
        }
      }));
  } else {
    let authMethod = await authMethodPromise;
    if (authMethod[authMethod.length - 1] === '/') {
      authMethod = authMethod.substring(0, authMethod.length - 1);
    }
    return authMethod;
  }
}

let authRealmPromise: Promise<string>;

export async function getAuthRealm(): Promise<string> {
  if (!authRealmPromise) {
    return (authRealmPromise = axios
      .get(envUrl)
      .then((overrides) => {
        if (overrides.data.AUTH_REALM) {
          let authRealm = overrides.data.AUTH_REALM;
          return authRealm;
        } else {
          if (process.env.authRealm) {
            return process.env.authRealm;
          } else {
            throw new Error('No authRealm defined');
          }
        }
      })
      .catch((err) => {
        if (process.env.environment === 'local') {
          return (authRealmPromise = Promise.resolve(process.env.authRealm));
        } else {
          console.error(`Failed to get authRealm url. Error: ${err.message}`);
          authRealmPromise = null;
          throw err;
        }
      }));
  } else {
    let authRealm = await authRealmPromise;
    if (authRealm[authRealm.length - 1] === '/') {
      authRealm = authRealm.substring(0, authRealm.length - 1);
    }
    return authRealm;
  }
}

let authUrlPromise: Promise<string>;

export async function getAuthUrl(): Promise<string> {
  if (!authUrlPromise) {
    return (authUrlPromise = axios
      .get(envUrl)
      .then((overrides) => {
        if (overrides.data.AUTH_URL) {
          let authUrl = overrides.data.AUTH_URL;
          return authUrl;
        } else {
          if (process.env.authUrl) {
            return process.env.authUrl;
          } else {
            throw new Error('No authUrl defined');
          }
        }
      })
      .catch((err) => {
        if (process.env.environment === 'local') {
          return (authUrlPromise = Promise.resolve(process.env.authUrl));
        } else {
          console.error(`Failed to get authUrl. Error: ${err.message}`);
          authUrlPromise = null;
          throw err;
        }
      }));
  } else {
    let authUrl = await authUrlPromise;
    if (authUrl[authUrl.length - 1] === '/') {
      authUrl = authUrl.substring(0, authUrl.length - 1);
    }
    return authUrl;
  }
}

let authClientIdPromise: Promise<string>;

export async function getAuthClientId(): Promise<string> {
  if (!authClientIdPromise) {
    return (authClientIdPromise = axios
      .get(envUrl)
      .then((overrides) => {
        if (overrides.data.AUTH_CLIENT_ID) {
          let authClientId = overrides.data.AUTH_CLIENT_ID;
          return authClientId;
        } else {
          if (process.env.authClientId) {
            return process.env.authClientId;
          } else {
            throw new Error('No authClientId defined');
          }
        }
      })
      .catch((err) => {
        if (process.env.environment === 'local') {
          return (authClientIdPromise = Promise.resolve(
            process.env.authClientId,
          ));
        } else {
          console.error(`Failed to get authClientId. Error: ${err.message}`);
          authClientIdPromise = null;
          throw err;
        }
      }));
  } else {
    let authClientId = await authClientIdPromise;
    if (authClientId[authClientId.length - 1] === '/') {
      authClientId = authClientId.substring(0, authClientId.length - 1);
    }
    return authClientId;
  }
}

let readOnlyPromise: Promise<boolean>;
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
  const apiUrl = await getAPIUrl();
  if (!readOnlyPromise) {
    return (readOnlyPromise = axios
      .get(envUrl)
      .then((overrides) => {
        if (overrides.data.READ_ONLY_DOMAIN) {
          let readOnlyDomain = overrides.data.READ_ONLY_DOMAIN.split(',');
          return readOnlyDomain?.includes(apiUrl);
        } else {
          if (process.env.readOnlyDomain) {
            let readOnlyDomain = process.env.readOnlyDomain.split(',');
            return readOnlyDomain?.includes(apiUrl);
          } else {
            throw new Error('No readOnly defined');
          }
        }
      })
      .catch((err) => {
        if (process.env.environment === 'local') {
          let readOnlyDomain = process.env.readOnlyDomain.split(',');
          return (readOnlyPromise = Promise.resolve(
            readOnlyDomain?.includes(apiUrl),
          ));
        } else {
          console.error(`Failed to get readOnly. Error: ${err.message}`);
          readOnlyPromise = null;
          throw err;
        }
      }));
  } else {
    return await readOnlyPromise;
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
