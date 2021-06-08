import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import useStyles from './EditorButton.styles';
import clsx from 'clsx';
import { ClassNameMap } from '@material-ui/styles/withStyles';

interface IProps {
  classes?: ClassNameMap;
  onClick(): void;
}

const UndoButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      onClick={props.onClick}
      className={clsx(classes.button, props.classes?.button)}>
      <FontAwesomeIcon
        className={classes.icon}
        icon={['fas', 'undo']}
        color={BLUE_COLOR}
      />
    </div>
  );
};

const ComposedUndoButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('UndoButton'),
)(UndoButton);

export default ComposedUndoButton;
