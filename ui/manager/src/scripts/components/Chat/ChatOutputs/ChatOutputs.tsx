import * as React from 'react';
import ChatOutput from '../ChatOutput/ChatOutput';
import Delayed from '../Delay/Delay';
import useStyles from './ChatOutputs.styles';

interface IChatOutputs {
  input: string;
  outputs: { text: string }[];
}

const ChatOutputs = ({ outputs, input }: IChatOutputs) => {
  const classes = useStyles();
  return (
    <div className={classes.chatOutputs}>
      <Delayed wait={500}>
        {!!input && <ChatOutput output={{ text: input }} input />}
      </Delayed>
      {outputs?.map((o, i) => {
        return (
          <Delayed wait={500 * (i + 2)} key={JSON.stringify(o) + i}>
            <ChatOutput output={o} />
          </Delayed>
        );
      })}
    </div>
  );
};

export default ChatOutputs;
