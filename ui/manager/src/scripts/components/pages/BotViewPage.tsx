import * as React from 'react';
import BotInfo from '../BotDetailView/BotInfo';

interface IRouteProps {
  match: { params: { id: string } };
}
interface IProps extends IRouteProps {}

const BotViewPage = (props: IProps) => (
  <div>
    <BotInfo botId={props.match.params.id} />
  </div>
);

export default BotViewPage;
