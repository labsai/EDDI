import { call, put, takeEvery } from 'redux-saga/effects';
import {
  clearChatAction,
  IReplyInChatAction,
  IRestartChatAction,
  IStartChatAction,
  replyInChatFailedAction,
  replyInChatSuccessAction,
  restartChatFailedAction,
  restartChatSuccessAction,
  startChatFailedAction,
  startChatSuccessAction,
} from '../actions/ChatActions';
import {
  REPLY_IN_CHAT,
  RESTART_CHAT,
  START_CHAT,
} from '../actions/ChatActionsTypes';
import {
  axiosReplyInChat,
  axiosRestartChat,
  axiosStartChat,
  getConversations,
} from '../components/utils/AxiosFunctions';

export function* startChat(action: IStartChatAction): Iterator<{}> {
  try {
    yield put(clearChatAction());
    const data: any = yield call(axiosStartChat, action.botId, action.context);

    yield put(startChatSuccessAction(data));
  } catch (err) {
    yield put(startChatFailedAction(err));
  }
}

export function* getPreviousConversation(botResource: string) {
  return yield call(getConversations, 2, 0, null, botResource);
}

export function* restartChat(action: IRestartChatAction): Iterator<{}> {
  try {
    yield put(clearChatAction());
    const data: any = yield call(
      axiosRestartChat,
      action.botId,
      action.conversationId,
    );
    yield put(restartChatSuccessAction(data));
  } catch (err) {
    yield put(restartChatFailedAction(err));
  }
}
export function* replyInChat(action: IReplyInChatAction): Iterator<{}> {
  try {
    const data: any = yield call(
      axiosReplyInChat,
      action.botId,
      action.conversationId,
      action.input,
      action.context,
    );
    yield put(replyInChatSuccessAction(data));
  } catch (err) {
    yield put(replyInChatFailedAction(err));
  }
}

export function* watchChat(): Iterator<{}> {
  yield takeEvery(START_CHAT, startChat);
  yield takeEvery(RESTART_CHAT, restartChat);
  yield takeEvery(REPLY_IN_CHAT, replyInChat);
}
