import axios from 'axios';

let apiUrlPromise: Promise<string>;

export async function getAPIUrl(): Promise<string> {
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

export const DEFAULT_LIMIT: number = 10;
