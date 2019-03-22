import * as Keycloak from 'keycloak-js';
import { setDefaultGlobalHeader } from './AxiosFunctions';
import {
  getAuthClientId,
  getAuthMethod,
  getAuthRealm,
  getAuthUrl,
} from './ApiFunctions';

export async function createKeycloakInstance(): Promise<
  Keycloak.KeycloakInstance
> {
  try {
    const keycloak = await Keycloak({
      url: await getAuthUrl(),
      realm: await getAuthRealm(),
      clientId: await getAuthClientId(),
    });
    return keycloak;
  } catch (err) {
    console.error('Failed to create keycloak instance.');
  }
}

export async function initKeycloak(
  keycloak: Keycloak.KeycloakInstance,
  onAuthentication: () => void,
) {
  try {
    await keycloak.init({ onLoad: 'login-required' }).success(() => {
      onAuthentication();
      setAuthorizationHeader(keycloak);
    });
  } catch (err) {
    console.error(`Failed to initialize keycloak. Error: ${err.message}`);
  }
}

export function setAuthorizationHeader(kc: Keycloak.KeycloakInstance): void {
  setDefaultGlobalHeader('Authorization', 'Bearer ' + kc.token);
}

export async function updateToken(kc: Keycloak.KeycloakInstance) {
  try {
    await kc
      .updateToken(300)
      .success(() => setAuthorizationHeader(kc))
      .error(() => {
        throw new Error('Failed to update token.');
      });
  } catch (err) {
    console.error(`Failed to update token. Error: ${err.message}`);
  }
}

export function logout(keycloak: Keycloak.KeycloakInstance): void {
  keycloak.logout();
}

export async function keycloakEnabled() {
  return (await getAuthMethod()) === 'keycloak';
}
