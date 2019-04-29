import createBrowserHistory from 'history/createBrowserHistory';
import { getApiUrlQuery } from './components/utils/ApiFunctions';
import * as _ from 'lodash';

export const history = createBrowserHistory();

export function historyPush(path: string, queryParams?: string[]) {
  const apiUrlQuery = `apiUrl=${encodeURIComponent(getApiUrlQuery())}`;
  let queryParameterList = [];
  if (!_.isEmpty(apiUrlQuery)) {
    queryParameterList.push(apiUrlQuery);
  }
  if (!_.isEmpty(queryParams)) {
    queryParameterList = queryParameterList.concat(queryParams);
  }
  const queryParameterString = `${
    _.isEmpty(queryParameterList) ? '' : `?${queryParameterList.join('&')}`
  }`;
  history.push(path + queryParameterString);
}
