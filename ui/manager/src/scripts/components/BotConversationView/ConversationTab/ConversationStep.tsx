import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { connect } from 'react-redux';
import styles from './ConversationStep.styles';
import * as test from '../test.json';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { CONVERSATION, CONVERSATION_PATH } from '../../utils/EddiTypes';
import {
  IAction,
  IConversation,
  IConversationOutput,
  IConversationStep,
  IConversationSteps,
  IInput,
  IOutput,
  IQuickReplies,
} from '../../utils/AxiosFunctions';
import { conversationSelector } from '../../../selectors/ConversationSelectors';
import * as renderIf from 'render-if';
import ReactJson from 'react-json-view';
import * as Radium from 'radium';
import { LIGHT_GREY_COLOR } from '../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

interface IProps {
  conversationStep: IConversationSteps;
  conversationOutput: IConversationOutput;
}

interface IState {
  action: string[];
  input: string;
  output: string[];
  quickReplies: string[];
  timeSpan: string;
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
    const action = this.getAction();
    const input = this.getInput();
    const output = this.getOutput();
    const quickReplies = this.getQuickReplies();
    const timeSpan = this.getTimespan();
    this.setState({ action, input, output, quickReplies, timeSpan });
  }

  getInput() {
    const lifecycleTasks = this.props.conversationStep.conversationStep;
    const inputTask = lifecycleTasks.find(lifecycleTask =>
      lifecycleTask.key.includes('input'),
    );
    if (inputTask) {
      return (inputTask as IInput).value;
    }
  }

  getOutput() {
    const lifecycleTasks = this.props.conversationStep.conversationStep;
    let i = lifecycleTasks.findIndex(lifecycleTask =>
      lifecycleTask.key.includes('output'),
    );
    const outputs = [];
    if (i < 0) {
      return;
    }
    outputs.push((lifecycleTasks[i] as IOutput).value);
    if (!parseInt(lifecycleTasks[0].key.split(/[:]+/).pop(), 10)) {
      return outputs;
    }
    i++;
    while (parseInt(lifecycleTasks[i].key.split(/[:]+/).pop(), 10)) {
      outputs.push((lifecycleTasks[i] as IOutput).value);
      i++;
    }
    return outputs;
  }

  getQuickReplies() {
    const lifecycleTasks = this.props.conversationStep.conversationStep;
    const quickRepliesTask = lifecycleTasks.find(lifecycleTask =>
      lifecycleTask.key.includes('quickReplies'),
    );
    if (quickRepliesTask) {
      return (quickRepliesTask as IQuickReplies).value.map(
        quickReply => quickReply.value,
      );
    }
  }

  getTimespan() {
    const lifecycleTasks = this.props.conversationStep.conversationStep;
    let firstLifecycleTaskIndex = lifecycleTasks.findIndex(
      lifecycleTask => !lifecycleTask.key.includes('properties'),
    );
    if (lifecycleTasks.length - firstLifecycleTaskIndex < 2) {
      return;
    }
    const timeSpan =
      lifecycleTasks[lifecycleTasks.length - 1].timestamp -
      lifecycleTasks[firstLifecycleTaskIndex].timestamp;
    return timeSpan > 999
      ? `${(timeSpan / 1000).toFixed(2)}s`
      : `${timeSpan}ms`;
  }

  getAction() {
    const lifecycleTasks = this.props.conversationStep.conversationStep;
    let actionTask = lifecycleTasks.find(lifecycleTask =>
      lifecycleTask.key.includes('actions'),
    );
    if (!actionTask) {
      return;
    }
    return (actionTask as IAction).value;
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
              <div style={styles.timeSpan}>{`${this.state.timeSpan}`}</div>
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
