import axios from 'axios';
import {
  BEHAVIOR,
  BEHAVIOR_PATH,
  BOT,
  BOT_PATH,
  HTTPCALLS,
  HTTPCALLS_PATH,
  OUTPUT,
  OUTPUT_PATH,
  PACKAGE,
  PACKAGE_PATH,
  REGULAR_DICTIONARY,
  REGULAR_DICTIONARY_PATH,
} from './EddiTypes';
import * as _ from 'lodash';

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
      .get('/_/env.json')
      .then(overrides => {
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
      .catch(err => {
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
      .get('/_/env.json')
      .then(overrides => {
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
      .catch(err => {
        if (process.env.environment === 'local') {
          return (authMethodPromise = Promise.resolve(process.env.authMethod));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
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
      .get('/_/env.json')
      .then(overrides => {
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
      .catch(err => {
        if (process.env.environment === 'local') {
          return (authRealmPromise = Promise.resolve(process.env.authRealm));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
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
      .get('/_/env.json')
      .then(overrides => {
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
      .catch(err => {
        if (process.env.environment === 'local') {
          return (authUrlPromise = Promise.resolve(process.env.authUrl));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
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
      .get('/_/env.json')
      .then(overrides => {
        if (overrides.data.AUTH_CLIENT_ID) {
          let authClientId = overrides.data.AUTH_CLIENT_ID;
          return authClientId;
        } else {
          if (process.env.authClientId) {
            return process.env.authClientId;
          } else {
            throw new Error('No authUrl defined');
          }
        }
      })
      .catch(err => {
        if (process.env.environment === 'local') {
          return (authClientIdPromise = Promise.resolve(
            process.env.authClientId,
          ));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
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
    case BOT:
      return BOT_PATH;
    case PACKAGE:
      return PACKAGE_PATH;
    default:
      return null;
  }
}

export const DEFAULT_LIMIT: number = 10;
