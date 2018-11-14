import * as React from 'react';
import BotInfo from '../BotDetailView/BotInfo';
import Parser from '../utils/Parser';

interface IRouteProps {
  match: { params: { id: string } };
  location: { search: string };
}
function getVersion(search: string) {
  const queryStrings = Parser.getQueryStrings(search);
  return queryStrings.version;
}
interface IProps extends IRouteProps {}

const BotViewPage = (props: IProps) => (
  <div>
    <BotInfo
      botId={props.match.params.id}
      botVersion={getVersion(props.location.search)}
    />
  </div>
);

export default BotViewPage;
