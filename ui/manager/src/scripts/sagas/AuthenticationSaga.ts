import { call, put, takeEvery } from 'redux-saga/effects';
import { basicAuthSignIn } from '../components/utils/AxiosFunctions';
import {
  basicAuthSignInFailedAction,
  basicAuthSignInSuccessAction,
  IBasicAuthSignInAction,
  IKeycloakRefreshTokenAction,
  IKeycloakSignInAction,
  keycloakRefreshTokenFailedAction,
  keycloakRefreshTokenSuccessAction,
  keycloakSignInFailedAction,
  keycloakSignInSuccessAction,
} from '../actions/AuthenticationActions';
import {
  BASIC_AUTH_SIGN_IN,
  KEYCLOAK_REFRESH_TOKEN,
  KEYCLOAK_SIGN_IN,
} from '../actions/AuthenticationActionTypes';
import {
  createKeycloakInstance,
  initKeycloak,
  updateToken,
} from '../components/utils/keycloakFunctions';

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
    const keycloak = yield call(createKeycloakInstance);
    yield call(initKeycloak, keycloak, null);
    yield put(keycloakSignInSuccessAction(keycloak));
  } catch (err) {
    yield put(keycloakSignInFailedAction(err));
  }
}

export function* watchKeycloakSignIn(): Iterator<{}> {
  yield takeEvery(KEYCLOAK_SIGN_IN, KeycloakSignIn);
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
