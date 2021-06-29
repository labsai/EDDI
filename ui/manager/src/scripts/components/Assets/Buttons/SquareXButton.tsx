import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR,
  LARGE_FONT2,
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR,
  RED_BORDER,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  container: {
    '&:hover': {
      color: RED_COLOR,
      border: RED_BORDER,
    },
    '&:focus': {
      color: RED_COLOR,
      border: RED_BORDER,
    },
    backgroundColor: WHITE_COLOR,
    color: BLUE_COLOR,
    cursor: 'pointer',
    fontSize: LARGE_FONT2,
    position: 'relative',
    border: LIGHT_GREY_BORDER,
    borderRadius: '4px',
    height: '24px',
    width: '24px',
  },
  text: {
    fontSize: '30px',
    textAlign: 'center',
    marginLeft: 'auto',
    marginRight: 'auto',
    marginTop: '-10px',
  },
});

interface IProps {
  classes?: ClassNameMap;
  onClick(): void;
}

const SquareXButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      onClick={props.onClick}
      className={clsx(classes.container, props.classes?.button)}>
      <div className={classes.text}>&times;</div>
    </div>
  );
};

const ComposedSquareXButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('SquareXButton'),
)(SquareXButton);

export default ComposedSquareXButton;
