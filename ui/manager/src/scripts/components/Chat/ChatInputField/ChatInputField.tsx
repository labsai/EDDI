import TextField from '@material-ui/core/TextField';
import * as React from 'react';
import useScrollIntoView from '../../utils/useScrollIntoView';
import Delayed from '../Delay/Delay';
import useStyles from './ChatInputField.styles';

interface IChatInputField {
  handleReplyInChat: ({ value }: { value: string }) => void;
  delay: number;
}

const ChatInputField = ({ handleReplyInChat, delay }: IChatInputField) => {
  const chatInputRef = React.useRef<HTMLFormElement>(null);
  const classes = useStyles();
  const [value, setValue] = React.useState<string>('');

  const handleSubmit = (e) => {
    e.preventDefault();
    handleReplyInChat({ value });
  };
  const handleChange = (e) => {
    setValue(e.target.value);
  };

  useScrollIntoView(chatInputRef);

  return (
    <Delayed wait={delay}>
      <form
        ref={chatInputRef}
        className={classes.chatInputContainer}
        noValidate
        autoComplete="off"
        onSubmit={handleSubmit}>
        <TextField
          size="small"
          value={value}
          onChange={handleChange}
          className={classes.chatInput}
          variant="outlined"
          placeholder="Type your response..."
          autoFocus
        />
      </form>
    </Delayed>
  );
};

export default ChatInputField;
