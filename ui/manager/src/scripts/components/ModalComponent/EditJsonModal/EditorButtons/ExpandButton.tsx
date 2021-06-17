import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { LIGHT_GREY_COLOR } from '../../../../../styles/DefaultStylingProperties';
import useStyles from './EditorButton.styles';

interface IProps {
  expanded: boolean;
  classes?: ClassNameMap;
  onClick(): void;
}

const ExpandButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      onClick={props.onClick}
      className={clsx(classes.button, props.classes?.button)}>
      <FontAwesomeIcon
        className={classes.icon}
        icon={['fas', props.expanded ? 'compress' : 'expand']}
        color={LIGHT_GREY_COLOR}
      />
    </div>
  );
};

const ComposedExpandButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ExpandButton'),
)(ExpandButton);

export default ComposedExpandButton;
