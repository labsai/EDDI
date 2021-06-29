import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import Button from './Button';

const useStyles = makeStyles({
  button: {
    border: `1px solid transparent`,
    backgroundColor: YELLOW_COLOR,
    color: WHITE_COLOR,
    transition: 'background-color 0.3s ease, color 0.3s ease',

    '&:disabled': {
      cursor: 'default',
      color: WHITE_COLOR,
      opacity: 0.6,
    },
    '&:hover': {
      backgroundColor: 'transparent',
      border: `2px solid ${YELLOW_COLOR}`,
      color: YELLOW_COLOR,
      transition: 'background-color 0.3s ease, color 0.3s ease',
    },
    '&:active': {
      backgroundColor: 'transparent',
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
