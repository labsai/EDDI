import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { useStyles, rjvStyles } from './ConversationStep.styles';
import {
  IConversationOutput,
  IConversationStep,
  IConversationSteps,
  IOutputValue,
} from '../../utils/AxiosFunctions';
import ReactJson from 'react-json-view';
import {
  DARK_YELLOW_COLOR,
  LIGHT_GREY_COLOR,
  ORANGE_COLOR,
  RED_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import ConversationHelper from '../../utils/helpers/ConversationHelper';

interface IProps {
  showAction: boolean;
  conversationStep: IConversationSteps;
  conversationOutput: IConversationOutput;
}

const ConversationStep = ({
  showAction,
  conversationStep,
  conversationOutput,
}: IProps) => {
  const [action, setAction] = React.useState<string>(null);
  const [input, setInput] = React.useState<string>(null);
  const [output, setOutput] = React.useState<IOutputValue[]>(null);
  const [quickReplies, setQuickReplies] = React.useState<string[]>(null);
  const [timeSpan, setTimeSpan] = React.useState<number>(null);
  const [expanded, setExpanded] = React.useState<boolean>(false);
  const [conversationOutputExpanded, setConversationOutputExpanded] =
    React.useState<boolean>(true);
  const [conversationStepExpanded, setConversationStepExpanded] =
    React.useState<boolean>(false);

  const classes = useStyles();

  React.useEffect(() => {
    const tempConversationStep: IConversationStep[] =
      conversationStep.conversationStep;
    const action = ConversationHelper.getAction(tempConversationStep);
    const input = ConversationHelper.getInput(tempConversationStep);
    const output = ConversationHelper.getOutput(conversationOutput);
    const quickReplies = ConversationHelper.getQuickReplies(conversationOutput);
    const timeSpan = ConversationHelper.getTimespan(tempConversationStep);
    setAction(action);
    setInput(input);
    setOutput(output);
    setQuickReplies(quickReplies);
    setTimeSpan(timeSpan);
  }, []);

  const getTimeColor = (timeSpan: number) => {
    if (timeSpan > 1999) {
      return { color: RED_COLOR };
    }
    if (timeSpan > 999) {
      return { color: ORANGE_COLOR };
    }
    if (timeSpan > 499) {
      return { color: DARK_YELLOW_COLOR };
    }
  };

  const expand = () => {
    setExpanded(!expanded);
  };

  const expandOutput = () => {
    setConversationOutputExpanded(!conversationOutputExpanded);
  };

  const expandStep = () => {
    setConversationStepExpanded(!conversationStepExpanded);
  };

  const getOutputRender = (output: IOutputValue, key: number) => {
    if (output.type === 'image' || output.type === 'botIcon') {
      return <img src={output.text} alt={output.type} key={key} />;
    } else {
      return (
        <div className={classes.output} key={key}>
          {output.text}
        </div>
      );
    }
  };

  return (
    <div className={classes.container}>
      <div className={classes.content} onClick={() => expand()}>
        <div className={classes.container}>
          <div className={classes.chatStep}>
            {((!input && action) || showAction) && (
              <div className={classes.actions}>
                <div className={classes.actionTitle}>{'Actions:'}</div>
                <div className={classes.action}>{action}</div>
              </div>
            )}
            {!!input || <div className={classes.input}>{input}</div>}
            {!!output && (
              <div>{output.map((output, i) => getOutputRender(output, i))}</div>
            )}
            {!!quickReplies && (
              <div>
                <div className={classes.quickReplies}>
                  {quickReplies.map((quickReply, i) => (
                    <div key={i} className={classes.quickReply}>
                      {quickReply}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
          <div className={classes.timeContainer}>
            <div className={classes.timeArrow}>
              <div className={classes.arrowLeft} />
            </div>
            <div
              style={getTimeColor(timeSpan)}
              className={
                classes.timeSpan
              }>{`${ConversationHelper.convertTimespan(timeSpan)}`}</div>
          </div>
        </div>
        {expanded && (
          <div
            className={classes.jsonView}
            onClick={(e) => e.stopPropagation()}>
            <div>
              <div
                key={'ConversationOutputTitle'}
                className={classes.titleContainer}
                onClick={() => expandOutput()}>
                <div className={classes.titleBox} />
                <div className={classes.titleText}>{`Conversation Output`}</div>
                <div className={classes.titleBox}>
                  <FontAwesomeIcon
                    className={classes.icon}
                    icon={[
                      'fas',
                      conversationOutputExpanded ? 'minus' : 'plus',
                    ]}
                    color={LIGHT_GREY_COLOR}
                  />
                </div>
              </div>
              {conversationOutputExpanded && (
                <ReactJson
                  style={rjvStyles.rjv}
                  src={conversationOutput}
                  theme={'monokai'}
                  collapsed={4}
                  displayDataTypes={false}
                  enableClipboard={false}
                />
              )}
            </div>
            <div>
              <div
                key={'ConversationStepTitle'}
                className={classes.titleContainer}
                onClick={() => expandStep()}>
                <div className={classes.titleBox} />
                <div className={classes.titleText}>{`Conversation Step`}</div>
                <div className={classes.titleBox}>
                  <FontAwesomeIcon
                    className={classes.icon}
                    icon={['fas', conversationStepExpanded ? 'minus' : 'plus']}
                    color={LIGHT_GREY_COLOR}
                  />
                </div>
              </div>
              {conversationStepExpanded && (
                <ReactJson
                  style={rjvStyles.rjv}
                  src={conversationStep}
                  theme={'monokai'}
                  collapsed={4}
                  displayDataTypes={false}
                  enableClipboard={false}
                />
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

const ComposedConversationStep: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ConversationStep'),
)(ConversationStep);

export default ComposedConversationStep;
