import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import { IConversationProperties } from '../../utils/AxiosFunctions';
import { CSSProperties } from 'react';
import {
  DARK_BLUE_BORDER,
  DARK_BLUE_COLOR,
  DARK_GREY_COLOR,
  MEDIUM_FONT3,
  SMALL_FONT2,
} from '../../../../styles/DefaultStylingProperties';
import * as Radium from 'radium';

const styles: CSSProperties = {
  title: {
    fontSize: MEDIUM_FONT3,
    color: DARK_BLUE_COLOR,
    textAlign: 'center',
    margin: '20px 0px 20px 0px',
    borderBottom: DARK_BLUE_BORDER,
  },
  content: {
    marginBottom: '50px',
  },
  propertyTitle: {
    display: 'flex',
    color: DARK_GREY_COLOR,
    fontSize: SMALL_FONT2,
  },
  property: {
    width: '200px',
  },
  propertyValues: {
    display: 'flex',
  },
  propertyValue: {
    width: '200px',
    color: DARK_BLUE_COLOR,
    paddingBottom: '2px',
  },
};

interface IProps {
  conversationProperties: IConversationProperties;
}

class ConversationProperties extends React.Component<IProps> {
  render() {
    const keys = Object.keys(this.props.conversationProperties);
    return (
      <div style={styles.content}>
        <div style={styles.title}>
          {`Conversation Properties {${keys.length}}`}
        </div>
        <div style={styles.propertyTitle}>
          <div style={styles.property}>{'Name'}</div>
          <div style={styles.property}>{'Value'}</div>
          <div style={styles.property}>{'Scope'}</div>
        </div>
        <div>
          {keys.map((property, i) => (
            <div style={styles.propertyValues} key={i}>
              <div style={styles.propertyValue}>
                {this.props.conversationProperties[property].name}
              </div>
              <div style={styles.propertyValue}>
                {typeof this.props.conversationProperties[property].value ===
                'object'
                  ? '{Object}'
                  : this.props.conversationProperties[property].value}
              </div>
              <div style={styles.propertyValue}>
                {this.props.conversationProperties[property].scope}
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedConversationProperties: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('ConversationProperties'),
)(ConversationProperties);

export default ComposedConversationProperties;
