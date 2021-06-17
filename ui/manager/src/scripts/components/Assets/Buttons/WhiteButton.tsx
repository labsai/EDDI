import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Button from './Button';

const useStyles = makeStyles({
  button: {
    border: '1px solid #D8DDE6',
    backgroundColor: '#FFFFFF',
    color: '#0070D2',

    '&:disabled': {
      cursor: 'default',
      color: '#d8dde6',
    },
    '&:hover': {
      backgroundColor: '#E4E6E9',
    },
    '&:active': {
      backgroundColor: '#FFFFFF',
    },
  },
});

interface IProps {
  text: string;
  disabled?: boolean;
  customStyles?: {};
  classes?: ClassNameMap;
  onClick(): void;
}

const WhiteButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <Button
      text={props.text}
      onClick={props.onClick}
      disabled={props.disabled}
      classes={{
        button: clsx(classes.button, props.classes?.button),
      }}
      customStyles={props.customStyles}
    />
  );
};

const ComposedWhiteButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('WhiteButton'),
)(WhiteButton);

export default ComposedWhiteButton;
