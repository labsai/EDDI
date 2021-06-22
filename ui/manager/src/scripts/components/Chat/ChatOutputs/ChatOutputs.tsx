import * as React from 'react';
import ChatOutput from '../ChatOutput/ChatOutput';
import Delayed from '../Delay/Delay';
import useStyles from './ChatOutputs.styles';

interface IChatOutputs {
  outputs: { text: string }[];
}

const ChatOutputs = ({ outputs }: IChatOutputs) => {
  const classes = useStyles();
  return (
    <div className={classes.chatOutputs}>
      {outputs?.map((o, i) => {
        return (
          <Delayed
            wait={500 * (i + 2)}
            key={JSON.stringify(o) + i}
            showTyping={i === outputs.length - 1}>
            <ChatOutput output={o} />
          </Delayed>
        );
      })}
    </div>
  );
};

export default ChatOutputs;
