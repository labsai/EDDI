import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { BOT_VIEW } from '../../../constants/paths';
import { historyPush } from '../../../history';
import { botSelector } from '../../../selectors/BotSelectors';
import { IBot } from '../../utils/AxiosFunctions';
import NameAndVersion from './NameAndVersion';

interface IPublicProps {
  botResource: string;
  usedByOlderVersion?: boolean;
  isSmallName: boolean;
}

interface IPrivateProps extends IPublicProps {
  bot: IBot;
  isLoading: boolean;
  error: Error;
}

const Bot = (props: IPrivateProps) => (
  <NameAndVersion
    descriptor={props.bot}
    usedByOlderVersion={props.usedByOlderVersion}
    isSmallName={props.isSmallName}
    onClick={() => historyPush(`${BOT_VIEW.replace(':id', props.bot.id)}/`)}
  />
);

const ComposedBot: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(botSelector),
  setDisplayName('Bot'),
)(Bot);

export default ComposedBot;
