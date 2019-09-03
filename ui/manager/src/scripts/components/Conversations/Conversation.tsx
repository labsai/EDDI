import * as React from 'react';
import * as renderIf from 'render-if';
import * as Radium from 'radium';
import { Link, browserHistory } from 'react-router-dom';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as _ from 'lodash';
import * as moment from 'moment';
import styles from './Conversation.styles';
import { IConversation } from '../utils/AxiosFunctions';
import Parser from '../utils/Parser';
import { historyPush } from '../../history';

interface IProps {
  conversation: IConversation;
}

class Conversation extends React.Component<IProps> {
  render() {
    return (
      <div
        style={styles.conversation}
        onClick={() =>
          historyPush(
            `/conversationview/${Parser.getId(
              this.props.conversation.resource,
            )}`,
          )
        }>
        <div style={styles.conversationName}>
          {this.props.conversation.botName}
        </div>
        <div style={styles.conversationVersion}>{`V${Parser.getVersion(
          this.props.conversation.botResource,
        )}`}</div>
        <div style={styles.centerFlex} />
        <div style={styles.conversationStepSizeContainer}>
          <div style={styles.conversationStepSize}>
            {this.props.conversation.conversationStepSize}
          </div>
        </div>
        <div style={styles.environment}>
          {this.props.conversation.environment}
        </div>
        <div style={styles.conversationState}>
          {this.props.conversation.conversationState}
        </div>
        <div style={styles.lastModifiedOn}>
          {moment(this.props.conversation.lastModifiedOn).fromNow()}
        </div>
        <div style={styles.createdOn}>
          {moment(this.props.conversation.createdOn).format('DD.MM.YYYY')}
        </div>
      </div>
    );
  }
}

const ComposedConversation: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('Conversation'),
)(Conversation);

export default ComposedConversation;
