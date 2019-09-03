import * as React from 'react';
import TopBarComponent from '../TopBar/TopBarComponent';
import styles from '../App.style';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { pageEnum } from './pageEnum';
import ConversationList from '../Conversations/ConversationList';

interface IProps {}
interface IState {
  filterText: string;
}

const eddiLogo = require('../../../public/images/eddi-logo.png');

class ConversationsPage extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      filterText: '',
    };
  }

  filter = (text: string) => {
    this.setState({ filterText: text });
  };

  render() {
    return (
      <div>
        <img src={eddiLogo} style={styles.eddiLogo} />
        <div className="content">
          <TopBarComponent page={pageEnum.conversation} filter={this.filter} />
          <ConversationList filterText={this.state.filterText} />
        </div>
      </div>
    );
  }
}

const ComposedConversationsPage: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(pure, setDisplayName('ConversationsPage'))(ConversationsPage);

export default ComposedConversationsPage;
