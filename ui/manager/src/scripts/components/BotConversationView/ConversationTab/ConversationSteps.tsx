import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { GREY_COLOR } from '../../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import {
  IConversationOutput,
  IConversationSteps,
} from '../../utils/AxiosFunctions';
import ConversationStep from './ConversationStep';
import useStyles from './ConversationSteps.styles';

interface IProps {
  isLoading: boolean;
  conversationId: string;
  conversationSteps: IConversationSteps[];
  conversationOutputs: IConversationOutput[];
}

const ConversationSteps = ({
  conversationOutputs,
  conversationSteps,
  conversationId,
}: IProps) => {
  const classes = useStyles();

  const [showAllActions, setshowAllActions] = React.useState<boolean>(false);
  const [autoRefresh, setAutoRefresh] = React.useState<boolean>(false);
  const [autoRefreshInterval, setAutoRefreshInterval] =
    React.useState<number>(null);

  const toggleShowAllActions = () => {
    setshowAllActions(!showAllActions);
  };

  const toggleAutoRefresh = () => {
    if (!autoRefresh) {
      setAutoRefresh(true);
      setAutoRefreshInterval(
        window.setInterval(
          () =>
            eddiApiActionDispatchers.fetchConversationAction(conversationId),
          1000,
        ),
      );
    } else {
      window.clearInterval(autoRefreshInterval);
      setAutoRefresh(false);
      setAutoRefreshInterval(null);
    }
  };

  return (
    <div>
      <div className={classes.title}>{`Conversation Steps`}</div>
      <div className={classes.toolbar}>
        <div className={classes.conversationSettings}>
          <div className={classes.toggleBox}>
            <div
              className={classes.button}
              onClick={toggleShowAllActions}
              key={'showAllActions'}>
              {showAllActions && (
                <FontAwesomeIcon
                  className={classes.icon}
                  icon={['fas', 'check']}
                  color={GREY_COLOR}
                />
              )}
            </div>
            <div className={classes.toggleText}>{'Show all actions'}</div>
          </div>
          <div className={classes.toggleBox}>
            <div
              className={classes.button}
              onClick={toggleAutoRefresh}
              key={'autoRefresh'}>
              {autoRefresh && (
                <FontAwesomeIcon
                  className={classes.icon}
                  icon={['fas', 'check']}
                  color={GREY_COLOR}
                />
              )}
            </div>
            <div className={classes.toggleText}>{'Auto refresh'}</div>
          </div>
        </div>
      </div>
      {conversationSteps.map((conversationStep, i) => (
        <ConversationStep
          key={i}
          showAction={showAllActions}
          conversationStep={conversationStep}
          conversationOutput={conversationOutputs[i]}
        />
      ))}
    </div>
  );
};

const ComposedConversationSteps: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ConversationSteps'),
)(ConversationSteps);

export default ComposedConversationSteps;
