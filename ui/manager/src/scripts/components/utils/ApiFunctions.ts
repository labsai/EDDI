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

let authPromise: Promise<string>;

export async function getAuthMethod(): Promise<string> {
  if (!authPromise) {
    return (authPromise = axios
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
          return (authPromise = Promise.resolve(process.env.authMethod));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
          authPromise = null;
          throw err;
        }
      }));
  } else {
    let authMethod = await authPromise;
    if (authMethod[authMethod.length - 1] === '/') {
      authMethod = authMethod.substring(0, authMethod.length - 1);
    }
    return authMethod;
  }
}

export async function getAuthRealm(): Promise<string> {
  if (!authPromise) {
    return (authPromise = axios
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
          return (authPromise = Promise.resolve(process.env.authRealm));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
          authPromise = null;
          throw err;
        }
      }));
  } else {
    let authRealm = await authPromise;
    if (authRealm[authRealm.length - 1] === '/') {
      authRealm = authRealm.substring(0, authRealm.length - 1);
    }
    return authRealm;
  }
}

export async function getAuthUrl(): Promise<string> {
  if (!authPromise) {
    return (authPromise = axios
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
          return (authPromise = Promise.resolve(process.env.authUrl));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
          authPromise = null;
          throw err;
        }
      }));
  } else {
    let authUrl = await authPromise;
    if (authUrl[authUrl.length - 1] === '/') {
      authUrl = authUrl.substring(0, authUrl.length - 1);
    }
    return authUrl;
  }
}

export async function getAuthClientId(): Promise<string> {
  if (!authPromise) {
    return (authPromise = axios
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
          return (authPromise = Promise.resolve(process.env.authClientId));
        } else {
          console.error(`Failed to get API url. Error: ${err.message}`);
          authPromise = null;
          throw err;
        }
      }));
  } else {
    let authClientId = await authPromise;
    if (authClientId[authClientId.length - 1] === '/') {
      authClientId = authClientId.substring(0, authClientId.length - 1);
    }
    return authClientId;
  }
}

export const DEFAULT_LIMIT: number = 10;
