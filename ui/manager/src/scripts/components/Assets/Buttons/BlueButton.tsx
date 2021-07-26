import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import Button from './Button';

const useStyles = makeStyles({
  button: {
    border: 'none',
    color: '#FFFFFF',
    backgroundColor: '#007d9c',
    transition: 'background-color 0.3s ease',

    '&:disabled': {
      color: WHITE_COLOR,
      cursor: 'default',
      border: 'none',
      backgroundColor: BLUE_COLOR,
      opacity: 0.6,
    },
    '&:active': {
      backgroundColor: BLUE_COLOR,
    },
    '&:hover': {
      backgroundColor: 'transparent',
      border: `2px solid ${BLUE_COLOR}`,
      color: BLUE_COLOR,
      transition: 'background-color 0.3s ease',
    },
  },
});

interface IProps {
  text: string;
  disabled?: boolean;
  noTabIndex?: boolean;
  customStyles?: {};
  classes?: ClassNameMap;
  onClick(event: React.MouseEvent): void;
}

const BlueButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <Button
      text={props.text}
      noTabIndex
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
