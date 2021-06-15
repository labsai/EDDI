import { call, put, takeEvery } from 'redux-saga/effects';
import {
  IReplyInChatAction,
  IStartChatAction,
  replyInChatFailedAction,
  replyInChatSuccessAction,
  startChatFailedAction,
  startChatSuccessAction,
} from '../actions/ChatActions';
import { REPLY_IN_CHAT, START_CHAT } from '../actions/ChatActionsTypes';
import {
  axiosReplyInChat,
  axiosStartChat,
} from '../components/utils/AxiosFunctions';

export function* startChat(action: IStartChatAction): Iterator<{}> {
  try {
    const data: any = yield call(axiosStartChat, action.botId);
    yield put(startChatSuccessAction(data));
  } catch (err) {
    yield put(startChatFailedAction(err));
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
  yield takeEvery(REPLY_IN_CHAT, replyInChat);
}
