import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import { BLUE_COLOR } from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import useStyles from './EditorButton.styles';
import clsx from 'clsx';

interface IProps {
  classes?: ClassNameMap;
  onClick(): void;
}

const RedoButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      onClick={props.onClick}
      className={clsx(classes.button, props.classes)}>
      <FontAwesomeIcon
        className={classes.icon}
        icon={['fas', 'redo']}
        color={BLUE_COLOR}
      />
    </div>
  );
};

const ComposedRedoButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('RedoButton'),
)(RedoButton);

export default ComposedRedoButton;
