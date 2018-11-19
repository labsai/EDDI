import axios from 'axios';

let apiUrlPromise: Promise<string>;

export async function getAPIUrl(): Promise<string> {
  if (!apiUrlPromise) {
    return (apiUrlPromise = axios
      .get('/_/env.json')
      .then(overrides => {
        if (overrides.data.EDDI_API_URL) {
          return overrides.data.EDDI_API_URL;
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
    return apiUrlPromise;
  }
}

export const DEFAULT_LIMIT: number = 20;
