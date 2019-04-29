import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import { IBot } from '../../utils/AxiosFunctions';
import { connect } from 'react-redux';
import { botSelector } from '../../../selectors/BotSelectors';
import NameAndVersion from './NameAndVersion';
import { historyPush } from '../../../history';

interface IPublicProps {
  botResource: string;
  usedByOlderVersion: boolean;
  isSmallName: boolean;
}

interface IPrivateProps extends IPublicProps {
  bot: IBot;
  isLoading: boolean;
  error: Error;
}

class Bot extends React.Component<IPrivateProps> {
  render() {
    return (
      <NameAndVersion
        descriptor={this.props.bot}
        usedByOlderVersion={this.props.usedByOlderVersion}
        isSmallName={this.props.isSmallName}
        onClick={() => historyPush(`/botview/${this.props.bot.id}`)}
      />
    );
  }
}

const ComposedBot: Component<IProps> = compose<IProps>(
  pure,
  connect(botSelector),
  setDisplayName('Bot'),
)(Bot);

export default ComposedBot;
