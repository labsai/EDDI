import { call, put, takeEvery } from 'redux-saga/effects';
import {
  fetchBotLogsFailedAction,
  fetchBotLogsSuccessAction,
  IFetchBotLogsAction,
} from '../actions/EddiApiActions';
import { FETCH_BOT_LOGS } from '../actions/EddiApiActionTypes';
import { axiosGetBotLogs } from '../components/utils/AxiosFunctions';

export function* fetchBotLogs(action: IFetchBotLogsAction): Iterator<{}> {
  try {
    const data: any = yield call(
      axiosGetBotLogs,
      action.botId,
      action.environment,
      action.botVersion,
    );
    yield put(fetchBotLogsSuccessAction(data));
  } catch (err) {
    yield put(fetchBotLogsFailedAction(err));
  }
}

export function* watchBotLogs(): Iterator<{}> {
  yield takeEvery(FETCH_BOT_LOGS, fetchBotLogs);
}
