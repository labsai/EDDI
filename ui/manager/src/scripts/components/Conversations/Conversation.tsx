import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as _ from 'lodash';
import * as moment from 'moment';
import useStyles from './Conversation.styles';
import { IConversation } from '../utils/AxiosFunctions';
import Parser from '../utils/Parser';
import { historyPush } from '../../history';
import { CONVERSATION_VIEW } from '../../constants/paths';

interface IProps {
  conversation: IConversation;
}

const Conversation: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      className={classes.conversation}
      onClick={() =>
        historyPush(
          `${CONVERSATION_VIEW.replace(
            ':id',
            Parser.getId(props.conversation.resource),
          )}/`,
        )
      }>
      <div className={classes.conversationName}>
        {props.conversation.botName}
      </div>
      <div className={classes.conversationVersion}>{`V${Parser.getVersion(
        props.conversation.botResource,
      )}`}</div>
      <div className={classes.centerFlex} />
      <div className={classes.conversationStepSizeContainer}>
        <div className={classes.conversationStepSize}>
          {props.conversation.conversationStepSize}
        </div>
      </div>
      <div className={classes.environment}>
        {props.conversation.environment}
      </div>
      <div className={classes.conversationState}>
        {props.conversation.conversationState}
      </div>
      <div className={classes.lastModifiedOn}>
        {moment(props.conversation.lastModifiedOn).fromNow()}
      </div>
      <div className={classes.createdOn}>
        {moment(props.conversation.createdOn).format('DD.MM.YYYY')}
      </div>
    </div>
  );
};

const ComposedConversation: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('Conversation'),
)(Conversation);

export default ComposedConversation;
