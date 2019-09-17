import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import styles from './ConversationStep.styles';
import {
  IAction,
  IConversationOutput,
  IConversationStep,
  IConversationSteps,
  IInput,
  IOutput,
  IQuickReplies,
} from '../../utils/AxiosFunctions';
import * as renderIf from 'render-if';
import ReactJson from 'react-json-view';
import * as Radium from 'radium';
import {
  DARK_YELLOW_COLOR,
  LIGHT_GREY_COLOR,
  ORANGE_COLOR,
  RED_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { isNumber } from 'util';
import ConversationHelper from '../../utils/helpers/ConversationHelper';

interface IProps {
  conversationStep: IConversationSteps;
  conversationOutput: IConversationOutput;
}

interface IState {
  action: string[];
  input: string;
  output: string[];
  quickReplies: string[];
  timeSpan: number;
  expanded: boolean;
  conversationOutputExpanded: boolean;
  conversationStepExpanded: boolean;
}

class ConversationStep extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      action: null,
      input: null,
      output: null,
      quickReplies: null,
      timeSpan: null,
      expanded: false,
      conversationOutputExpanded: true,
      conversationStepExpanded: true,
    };
  }

  componentDidMount() {
    const conversationStep: IConversationStep[] = this.props.conversationStep
      .conversationStep;
    const action = ConversationHelper.getAction(conversationStep);
    const input = ConversationHelper.getInput(conversationStep);
    const output = ConversationHelper.getOutput(conversationStep);
    const quickReplies = ConversationHelper.getQuickReplies(conversationStep);
    const timeSpan = ConversationHelper.getTimespan(conversationStep);
    this.setState({ action, input, output, quickReplies, timeSpan });
  }

  getTimeColor(timeSpan: number) {
    if (timeSpan > 1999) {
      return { ...styles.timeSpan, color: RED_COLOR };
    }
    if (timeSpan > 999) {
      return { ...styles.timeSpan, color: ORANGE_COLOR };
    }
    if (timeSpan > 499) {
      return { ...styles.timeSpan, color: DARK_YELLOW_COLOR };
    }
    return styles.timeSpan;
  }

  expand() {
    this.setState({ expanded: !this.state.expanded });
  }

  expandOutput() {
    this.setState({
      conversationOutputExpanded: !this.state.conversationOutputExpanded,
    });
  }

  expandStep() {
    this.setState({
      conversationStepExpanded: !this.state.conversationStepExpanded,
    });
  }

  render() {
    return (
      <div style={styles.container}>
        <div style={styles.content} onClick={() => this.expand()}>
          <div style={styles.container}>
            <div style={styles.chatStep}>
              {renderIf(!this.state.input && this.state.action)(() => (
                <div style={styles.actions}>
                  <div style={styles.actionTitle}>{'Actions:'}</div>
                  {this.state.action.map((action, i) => (
                    <div key={i} style={styles.action}>
                      {action}
                    </div>
                  ))}
                </div>
              ))}
              {renderIf(this.state.input)(() => (
                <div style={styles.input}>{this.state.input}</div>
              ))}
              {renderIf(this.state.output)(() => (
                <div>
                  {this.state.output.map((output, i) => (
                    <div key={i} style={styles.output}>
                      {output}
                    </div>
                  ))}
                </div>
              ))}
              {renderIf(this.state.quickReplies)(() => (
                <div>
                  <div style={styles.quickReplies}>
                    {this.state.quickReplies.map((quickReply, i) => (
                      <div key={i} style={styles.quickReply}>
                        {quickReply}
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
            <div style={styles.timeContainer}>
              <div style={styles.timeArrow}>
                <div style={styles.arrowLeft} />
              </div>
              <div
                style={this.getTimeColor(
                  this.state.timeSpan,
                )}>{`${ConversationHelper.convertTimespan(
                this.state.timeSpan,
              )}`}</div>
            </div>
          </div>
          {renderIf(this.state.expanded)(() => (
            <div style={styles.jsonView} onClick={e => e.stopPropagation()}>
              <div>
                <div
                  key={'ConversationOutputTitle'}
                  style={styles.titleContainer}
                  onClick={() => this.expandOutput()}>
                  <div style={styles.titleBox} />
                  <div style={styles.titleText}>{`Conversation Output`}</div>
                  <div style={styles.titleBox}>
                    <FontAwesomeIcon
                      style={styles.icon}
                      icon={[
                        'fas',
                        this.state.conversationOutputExpanded
                          ? 'minus'
                          : 'plus',
                      ]}
                      color={LIGHT_GREY_COLOR}
                    />
                  </div>
                </div>
                {renderIf(this.state.conversationOutputExpanded)(() => (
                  <ReactJson
                    style={styles.rjv}
                    src={this.props.conversationOutput}
                    theme={'monokai'}
                    collapsed={4}
                    displayDataTypes={false}
                    enableClipboard={false}
                  />
                ))}
              </div>
              <div>
                <div
                  key={'ConversationStepTitle'}
                  style={styles.titleContainer}
                  onClick={() => this.expandStep()}>
                  <div style={styles.titleBox} />
                  <div style={styles.titleText}>{`Conversation Step`}</div>
                  <div style={styles.titleBox}>
                    <FontAwesomeIcon
                      style={styles.icon}
                      icon={[
                        'fas',
                        this.state.conversationStepExpanded ? 'minus' : 'plus',
                      ]}
                      color={LIGHT_GREY_COLOR}
                    />
                  </div>
                </div>
                {renderIf(this.state.conversationStepExpanded)(() => (
                  <ReactJson
                    style={styles.rjv}
                    src={this.props.conversationStep}
                    theme={'monokai'}
                    collapsed={4}
                    displayDataTypes={false}
                    enableClipboard={false}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedConversationStep: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('ConversationStep'),
)(ConversationStep);

export default ComposedConversationStep;
