import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import useStyles from '../App.style';
import ConversationList from '../Conversations/ConversationList';
import TopBarComponent from '../TopBar/TopBarComponent';
import { pageEnum } from './pageEnum';

const eddiLogo = require('../../../public/images/eddi-logo.png');

const ConversationsPage = () => {
  const [filterText, setFilterText] = React.useState('');

  const classes = useStyles();

  const filter = (text: string) => {
    setFilterText(text);
  };

  return (
    <div>
      <img src={eddiLogo} className={classes.eddiLogo} />
      <div className="content">
        <TopBarComponent page={pageEnum.conversation} filter={filter} />
        <ConversationList filterText={filterText} />
      </div>
    </div>
  );
};

const ComposedConversationsPage = compose(
  pure,
  setDisplayName('ConversationsPage'),
)(ConversationsPage);

export default ComposedConversationsPage;
