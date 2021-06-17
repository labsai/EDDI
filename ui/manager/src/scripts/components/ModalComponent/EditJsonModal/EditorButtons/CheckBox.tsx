import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { LIGHT_GREY_COLOR } from '../../../../../styles/DefaultStylingProperties';
import useStyles from './EditorButton.styles';

interface IProps {
  checked: boolean;
  classes?: ClassNameMap;
  onClick(): void;
}

const CheckBox: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      onClick={props.onClick}
      className={clsx(classes.button, props.classes.checkbox)}>
      {props.checked && (
        <FontAwesomeIcon
          className={classes.icon}
          icon={['fas', 'check']}
          color={LIGHT_GREY_COLOR}
        />
      )}
    </div>
  );
};

const ComposedCheckBox: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('CheckBox'),
)(CheckBox);

export default ComposedCheckBox;
