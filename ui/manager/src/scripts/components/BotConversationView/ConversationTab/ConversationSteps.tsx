import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import ReactJson from 'react-json-view';
import ConversationStep from './ConversationStep';
import {
  IConversationOutput,
  IConversationSteps,
} from '../../utils/AxiosFunctions';
import { CSSProperties } from 'react';
import {
  BLUE_COLOR,
  DARK_BLUE_COLOR,
  LIGHT_BLUE_COLOR2,
  LIGHT_GREY_COLOR,
  MEDIUM_FONT,
  MEDIUM_FONT2,
  MEDIUM_FONT3,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import * as Radium from 'radium';

interface IProps {
  conversationSteps: IConversationSteps[];
  conversationOutputs: IConversationOutput[];
}

class ConversationSteps extends React.Component<IProps> {
  constructor(props) {
    super(props);
  }

  render() {
    return (
      <div>
        {this.props.conversationSteps.map((conversationStep, i) => (
          <ConversationStep
            key={i}
            conversationStep={conversationStep}
            conversationOutput={this.props.conversationOutputs[i]}
          />
        ))}
      </div>
    );
  }
}

const ComposedConversationSteps: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('ConversationSteps'),
)(ConversationSteps);

export default ComposedConversationSteps;
