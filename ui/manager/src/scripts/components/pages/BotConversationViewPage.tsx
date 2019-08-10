import * as React from 'react';
import BotConversationView from '../BotConversationView/BotConversationView';
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

const BotConversationViewPage = (props: IProps) => (
  <div>
    <BotConversationView
      botId={props.match.params.id}
      botVersion={getVersion(props.location.search)}
    />
  </div>
);

export default BotConversationViewPage;
