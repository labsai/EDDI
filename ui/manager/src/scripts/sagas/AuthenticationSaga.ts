import { call, put, takeEvery } from 'redux-saga/effects';
import {
  basicAuthSignIn,
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
  keycloakRefreshTokenFailedAction,
  keycloakRefreshTokenSuccessAction,
  keycloakSignInFailedAction,
  keycloakSignInSuccessAction,
} from '../actions/AuthenticationActions';
import {
  BASIC_AUTH_SIGN_IN,
  CHECK_AUTHENTICATION,
  KEYCLOAK_REFRESH_TOKEN,
  KEYCLOAK_SIGN_IN,
} from '../actions/AuthenticationActionTypes';
import {
  createKeycloakInstance,
  initKeycloak,
  isKeycloakEnabled,
  updateToken,
} from '../components/utils/keycloakFunctions';
import { AuthenticationEnum } from '../reducers/AuthenticationReducer';

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
    console.log(action.keycloak);
    if (action.keycloak) {
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
    if (yield call(isKeycloakEnabled)) {
      const keycloak = yield call(createKeycloakInstance);
      yield put(
        checkAuthenticationSuccessAction(AuthenticationEnum.keycloak, keycloak),
      );
    } else if (yield call(isBasicAuthRequired)) {
      yield put(
        checkAuthenticationSuccessAction(AuthenticationEnum.basicAuth, null),
      );
    } else {
      yield put(
        checkAuthenticationSuccessAction(AuthenticationEnum.none, null),
      );
    }
  } catch (err) {
    yield put(checkAuthenticationFailedAction(err));
  }
}

export function* watchCheckAuthentication(): Iterator<{}> {
  yield takeEvery(CHECK_AUTHENTICATION, checkAuthentication);
}
