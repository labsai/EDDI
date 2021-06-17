import clsx from 'clsx';
import * as React from 'react';
import ChatButton from '../ChatButton/ChatButton';
import Delayed from '../Delay/Delay';
import useStyles from './ChatQuickReplies.styles';

const ChatQuickReplies = ({
  quickReplies,
  handleReplyInChat,
  hidden,
  delay,
}) => {
  const classes = useStyles();

  const handleQuickReplyClick = (quickReply) => {
    handleReplyInChat(quickReply);
  };

  return (
    <Delayed wait={delay}>
      <div
        className={clsx(classes.quickReplies, hidden ? classes.hidden : null)}>
        {quickReplies?.map((q, i: number) => {
          return (
            <ChatButton
              key={q + i}
              data={q}
              handleQuickReplyClick={handleQuickReplyClick}
            />
          );
        })}
      </div>
    </Delayed>
  );
};

export default ChatQuickReplies;
