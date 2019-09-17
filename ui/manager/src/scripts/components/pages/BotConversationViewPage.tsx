import * as React from 'react';
import BotConversationView from '../BotConversationView/BotConversationView';
import Parser from '../utils/Parser';

interface IRouteProps {
  match: { params: { id: string } };
  location: { search: string };
}
interface IProps extends IRouteProps {}
const BotConversationViewPage = (props: IProps) => (
  <div>
    <BotConversationView conversationId={props.match.params.id} />
  </div>
);

export default BotConversationViewPage;
