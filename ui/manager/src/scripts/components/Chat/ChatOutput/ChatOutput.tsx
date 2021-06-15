import * as React from 'react';
import useStyles from './ChatOutput.styles';
import clsx from 'clsx';

interface IChatOutput {
  output: { text: string };
  input?: boolean;
}

const ChatOutput = ({ output, input }: IChatOutput) => {
  const classes = useStyles();

  const outputText = typeof output === 'string' ? output : output.text;

  if (!outputText) {
    return null;
  }

  return (
    <div className={clsx(classes.chatOutput, input ? classes.chatInput : null)}>
      <p className={clsx(classes.outputText, input ? classes.inputText : null)}>
        {outputText}
      </p>
    </div>
  );
};

export default ChatOutput;
