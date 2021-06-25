import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR,
  LIGHT_GREY_COLOR2,
} from '../../../../styles/DefaultStylingProperties';
import Button from './Button';

const useStyles = makeStyles({
  button: {
    border: `1px solid ${LIGHT_GREY_COLOR2}`,
    backgroundColor: '#FFFFFF',
    color: BLUE_COLOR,

    '&:disabled': {
      cursor: 'default',
      color: LIGHT_GREY_COLOR2,
    },
    '&:hover': {
      backgroundColor: LIGHT_GREY_COLOR2,
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
