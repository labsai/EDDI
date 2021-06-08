import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Button from './Button';

const useStyles = makeStyles({
  button: {
    border: '0px',
    color: '#FFFFFF',
    backgroundColor: '#0070D2',

    '&:disabled': {
      backgroundColor: '#c4c9d2',
      cursor: 'default',
    },
    '&:active': {
      backgroundColor: '#4A90E2',
    },
    '&:hover': {
      backgroundColor: '#0070D2',
    },
  },
});

interface IProps {
  text: string;
  disabled?: boolean;
  customStyles?: {};
  classes?: ClassNameMap;
  onClick(event: React.MouseEvent): void;
}

const BlueButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <Button
      text={props.text}
      onClick={props.onClick}
      disabled={props.disabled}
      customStyles={props.customStyles}
      classes={{
        button: clsx(classes.button, props.classes?.button),
      }}
    />
  );
};

const ComposedBlueButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('BlueButton'),
)(BlueButton);

export default ComposedBlueButton;
