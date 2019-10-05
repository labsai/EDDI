import { call, put, takeEvery } from 'redux-saga/effects';
import {
  basicAuthSignIn,
  deleteGlobalHeader,
  isBasicAuthRequired,
} from '../components/utils/AxiosFunctions';
import {
  basicAuthSignInFailedAction,
  basicAuthSignInSuccessAction,
  checkAuthenticationFailedAction,
  checkAuthenticationSuccessAction,
  IBasicAuthSignInAction,
  ICheckAuthenticationAction,
  IKeycloakRefreshTokenAction,
  IKeycloakSignInAction,
  ISignOutAction,
  keycloakRefreshTokenFailedAction,
  keycloakRefreshTokenSuccessAction,
  keycloakSignInFailedAction,
  keycloakSignInSuccessAction,
  signOutFailedAction,
  signOutSuccessAction,
} from '../actions/AuthenticationActions';
import {
  BASIC_AUTH_SIGN_IN,
  CHECK_AUTHENTICATION,
  KEYCLOAK_REFRESH_TOKEN,
  KEYCLOAK_SIGN_IN,
  SIGN_OUT,
} from '../actions/AuthenticationActionTypes';
import {
  createKeycloakInstance,
  isKeycloakEnabled,
  logout,
  setAuthorizationHeader,
  updateToken,
} from '../components/utils/keycloakFunctions';
import { getReadOnly } from '../components/utils/ApiFunctions';

export function* BasicAuthSignIn(action: IBasicAuthSignInAction) {
  try {
    yield call(basicAuthSignIn, action.username, action.password);
    yield put(basicAuthSignInSuccessAction());
  } catch (err) {
    yield put(basicAuthSignInFailedAction(err));
  }
}

export function* watchBasicAuthSignIn(): Iterator<{}> {
  yield takeEvery(BASIC_AUTH_SIGN_IN, BasicAuthSignIn);
}

export function* KeycloakSignIn(action: IKeycloakSignInAction) {
  try {
    if (action.keycloak) {
      yield call(setAuthorizationHeader, action.keycloak);
      yield put(keycloakSignInSuccessAction(action.keycloak));
    } else {
      throw new Error('Failed to sign in with keycloak.');
    }
  } catch (err) {
    yield put(keycloakSignInFailedAction(err));
  }
}

export function* watchKeycloakSignIn(): Iterator<{}> {
  yield takeEvery(KEYCLOAK_SIGN_IN, KeycloakSignIn);
}

export function* SignOut(action: ISignOutAction) {
  try {
    if (action.keycloak) {
      yield call(logout, action.keycloak);
    }
    yield call(deleteGlobalHeader, 'Authorization');
    yield put(signOutSuccessAction());
  } catch (err) {
    yield put(signOutFailedAction(err));
  }
}

export function* watchSignOut(): Iterator<{}> {
  yield takeEvery(SIGN_OUT, SignOut);
}

export function* KeycloakRefreshToken(action: IKeycloakRefreshTokenAction) {
  try {
    yield call(updateToken, action.keycloak);
    yield put(keycloakRefreshTokenSuccessAction());
  } catch (err) {
    yield put(keycloakRefreshTokenFailedAction(err));
  }
}

export function* watchKeycloakRefreshToken(): Iterator<{}> {
  yield takeEvery(KEYCLOAK_REFRESH_TOKEN, KeycloakRefreshToken);
}

export function* checkAuthentication(
  action: ICheckAuthenticationAction,
): Iterator<{}> {
  try {
    const basicAuthEnabled = yield call(isBasicAuthRequired);
    const keycloakEnabled = yield call(isKeycloakEnabled);
    const isReadOnly = yield call(getReadOnly);
    const keycloak = keycloakEnabled
      ? yield call(createKeycloakInstance)
      : null;
    yield put(
      checkAuthenticationSuccessAction(
        keycloakEnabled,
        basicAuthEnabled,
        isReadOnly === 'true',
        keycloak,
      ),
    );
  } catch (err) {
    yield put(checkAuthenticationFailedAction(err));
  }
}

export function* watchCheckAuthentication(): Iterator<{}> {
  yield takeEvery(CHECK_AUTHENTICATION, checkAuthentication);
}
