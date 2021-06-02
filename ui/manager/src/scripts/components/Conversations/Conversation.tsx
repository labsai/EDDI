import * as React from 'react';
import * as Radium from 'radium';
import { compose, pure, setDisplayName } from 'recompose';
import * as _ from 'lodash';
import * as moment from 'moment';
import styles from './Conversation.styles';
import { IConversation } from '../utils/AxiosFunctions';
import Parser from '../utils/Parser';
import { historyPush } from '../../history';

interface IProps {
  conversation: IConversation;
}

const Conversation: React.StatelessComponent<IProps> = (props: IProps) => {
  return (
    <div
      style={styles.conversation}
      onClick={() =>
        historyPush(
          `/conversationview/${Parser.getId(props.conversation.resource)}`,
        )
      }>
      <div style={styles.conversationName}>{props.conversation.botName}</div>
      <div style={styles.conversationVersion}>{`V${Parser.getVersion(
        props.conversation.botResource,
      )}`}</div>
      <div style={styles.centerFlex} />
      <div style={styles.conversationStepSizeContainer}>
        <div style={styles.conversationStepSize}>
          {props.conversation.conversationStepSize}
        </div>
      </div>
      <div style={styles.environment}>{props.conversation.environment}</div>
      <div style={styles.conversationState}>
        {props.conversation.conversationState}
      </div>
      <div style={styles.lastModifiedOn}>
        {moment(props.conversation.lastModifiedOn).fromNow()}
      </div>
      <div style={styles.createdOn}>
        {moment(props.conversation.createdOn).format('DD.MM.YYYY')}
      </div>
    </div>
  );
};

const ComposedConversation: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('Conversation'),
)(Conversation);

export default ComposedConversation;
