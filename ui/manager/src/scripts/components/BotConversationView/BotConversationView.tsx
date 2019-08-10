import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IBot } from '../utils/AxiosFunctions';
import styles from './BotConversionView.styles';
import * as test from './test.json';

interface IProps {
  bot: IBot;
}

class BotConversationView extends React.Component<IProps> {
  render() {
    return (
      <div style={styles.content}>
        <div style={styles.chat}>
          <div style={styles.output}>
            {"I'm hapBot, I will give you tips on how to be happier."}
          </div>
          <div style={styles.output}>{'Might I know your name?'}</div>
          <div style={styles.input}>{'Jonas'}</div>
          <div style={styles.output}>
            {
              "Jonas! That's a good name. At least it makes sense than mine! ����"
            }
          </div>
          <div style={styles.output}>
            {
              "I wouldn't say that you ain't really happy. But I know that there may be room for improvement."
            }
          </div>
          <div style={styles.output}>
            {'Ready for this tour to discovering greater happiness?'}
          </div>
          <div style={styles.input}>{'Maybe'}</div>
          <div style={styles.output}>{'You are in for it!'}</div>
          <div style={styles.output}>
            {
              "Start by asking yourself this question: 'What do I want from life?'"
            }
          </div>
          <div style={styles.output}>
            {"Answer by saying: 'I WANT HAPPINESS.'"}
          </div>
          <div style={styles.output}>{'Ready for next step?'}</div>
          <div style={styles.quickSelectGroup}>
            <div style={styles.quickSelect}>{'Yes'}</div>
            <div style={styles.quickSelect}>{'Maybe'}</div>
            <div style={styles.quickSelected}>{'No'}</div>
          </div>
        </div>
        <div style={styles.data}>{JSON.stringify(test, null, '\t')}</div>
      </div>
    );
  }
}

const ComposedBotConversationView: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('BotConversationView'),
)(BotConversationView);

export default ComposedBotConversationView;
