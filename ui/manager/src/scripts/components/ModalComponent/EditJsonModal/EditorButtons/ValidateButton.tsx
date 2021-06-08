import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { LIGHT_GREY_COLOR } from '../../../../../styles/DefaultStylingProperties';
import useStyles from './EditorButton.styles';
import clsx from 'clsx';
import { ClassNameMap } from '@material-ui/styles/withStyles';

interface IProps {
  classes?: ClassNameMap;
  onClick(): void;
}

const ValidateButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div
      onClick={props.onClick}
      className={clsx(classes.validateButton, props.classes?.button)}>
      <FontAwesomeIcon
        className={classes.icon}
        icon={['fas', 'check-circle']}
        color={LIGHT_GREY_COLOR}
      />
      <div>{'Validate'}</div>
    </div>
  );
};

const ComposedValidateButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ValidateButton'),
)(ValidateButton);

export default ComposedValidateButton;
