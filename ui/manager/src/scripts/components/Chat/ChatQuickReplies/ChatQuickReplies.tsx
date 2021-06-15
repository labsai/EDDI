import { Button } from '@material-ui/core';
import * as React from 'react';
import useStyles from './ChatQuickReplies.styles';
import clsx from 'clsx';

const ChatQuickReplies = ({ quickReplies, handleReplyInChat, hidden }) => {
  const classes = useStyles();

  const handleQuickReplyClick = (quickReply) => {
    handleReplyInChat(quickReply);
  };

  return (
    <div className={clsx(classes.quickReplies, hidden ? classes.hidden : null)}>
      {quickReplies?.map((q, i) => {
        return (
          <Button
            key={q + i}
            size="small"
            variant="contained"
            color="primary"
            className={classes.quickRepliesButton}
            onClick={() => handleQuickReplyClick(q)}>
            {q.value}
          </Button>
        );
      })}
    </div>
  );
};

export default ChatQuickReplies;
