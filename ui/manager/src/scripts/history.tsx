import { createBrowserHistory } from 'history';
import { getApiUrlQuery } from './components/utils/ApiFunctions';
import * as _ from 'lodash';

export const history = createBrowserHistory();

export function historyPush(
  path: string,
  queryParams?: string[],
  blank?: boolean,
) {
  const apiUrlQuery = getApiUrlQuery();
  let queryParameterList = [];
  if (!_.isEmpty(apiUrlQuery)) {
    queryParameterList.push('apiUrl=' + encodeURIComponent(apiUrlQuery));
  }
  if (!_.isEmpty(queryParams)) {
    queryParameterList = queryParameterList.concat(queryParams);
  }
  const queryParameterString = `${
    _.isEmpty(queryParameterList) ? '' : `?${queryParameterList.join('&')}`
  }`;

  if (blank) {
    window.open(path + queryParameterString, '_blank');
  } else {
    history.push(path + queryParameterString);
  }
}
