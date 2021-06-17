import { Button } from '@material-ui/core';
import * as React from 'react';
import useScrollIntoView from '../../utils/useScrollIntoView';
import useStyles from '../ChatQuickReplies/ChatQuickReplies.styles';

const ChatButton = ({ data, handleQuickReplyClick }) => {
  const buttonsRef = React.useRef<HTMLButtonElement>(null);
  useScrollIntoView(buttonsRef, data);
  const classes = useStyles();

  return (
    <Button
      ref={buttonsRef}
      size="small"
      variant="contained"
      color="primary"
      className={classes.quickRepliesButton}
      onClick={() => handleQuickReplyClick(data)}>
      {data.value}
    </Button>
  );
};

export default ChatButton;
