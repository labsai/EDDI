import * as React from 'react';
import ChatOutput from '../ChatOutput/ChatOutput';

import useStyles from './ChatOutputs.styles';

const ChatOutputs = ({ outputs, input }) => {
  const classes = useStyles();
  return (
    <div className={classes.chatOutputs}>
      {!!input && <ChatOutput output={input} input />}
      {outputs.map((o, i) => {
        return <ChatOutput key={o + i} output={o} />;
      })}
    </div>
  );
};

export default ChatOutputs;
