import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import ConversationStep from './ConversationStep';
import {
  IConversationOutput,
  IConversationSteps,
} from '../../utils/AxiosFunctions';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import styles from './ConversationSteps.styles';
import Radium from 'radium';
import {
  BLUE_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';

interface IProps {
  isLoading: boolean;
  conversationId: string;
  conversationSteps: IConversationSteps[];
  conversationOutputs: IConversationOutput[];
}

interface IState {
  showAllActions: boolean;
  autoRefresh: boolean;
  autoRefreshInterval: number;
}

class ConversationSteps extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      showAllActions: false,
      autoRefresh: false,
      autoRefreshInterval: null,
    };
  }

  toggleShowAllActions() {
    this.setState({ showAllActions: !this.state.showAllActions });
  }

  toggleAutoRefresh() {
    if (!this.state.autoRefresh) {
      this.setState({
        autoRefresh: true,
        autoRefreshInterval: window.setInterval(
          () =>
            eddiApiActionDispatchers.fetchConversationAction(
              this.props.conversationId,
            ),
          1000,
        ),
      });
    } else {
      window.clearInterval(this.state.autoRefreshInterval);
      this.setState({
        autoRefresh: false,
        autoRefreshInterval: null,
      });
    }
  }

  render() {
    return (
      <div>
        <div style={styles.title}>{`Conversation Steps`}</div>
        <div style={styles.toolbar}>
          <div style={styles.conversationSettings}>
            <div style={styles.toggleBox}>
              <div
                style={styles.button}
                onClick={() => this.toggleShowAllActions()}
                key={'showAllActions'}>
                {renderIf(this.state.showAllActions)(() => (
                  <FontAwesomeIcon
                    style={styles.icon}
                    icon={['fas', 'check']}
                    color={GREY_COLOR}
                  />
                ))}
              </div>
              <div style={styles.toggleText}>{'Show all actions'}</div>
            </div>
            <div style={styles.toggleBox}>
              <div
                style={styles.button}
                onClick={() => this.toggleAutoRefresh()}
                key={'autoRefresh'}>
                {renderIf(this.state.autoRefresh)(() => (
                  <FontAwesomeIcon
                    style={styles.icon}
                    icon={['fas', 'check']}
                    color={GREY_COLOR}
                  />
                ))}
              </div>
              <div style={styles.toggleText}>{'Auto refresh'}</div>
            </div>
          </div>
        </div>
        {this.props.conversationSteps.map((conversationStep, i) => (
          <ConversationStep
            key={i}
            showAction={this.state.showAllActions}
            conversationStep={conversationStep}
            conversationOutput={this.props.conversationOutputs[i]}
          />
        ))}
      </div>
    );
  }
}

const ComposedConversationSteps: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  Radium,
  setDisplayName('ConversationSteps'),
)(ConversationSteps);

export default ComposedConversationSteps;
