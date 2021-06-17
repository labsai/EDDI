import Button from '@material-ui/core/Button';
import { makeStyles } from '@material-ui/core/styles';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';

const useStyles = makeStyles({
  button: {
    height: '35px',
    width: '108px',
    border: '1px solid #000',
    backgroundColor: '#CCC',
    color: '#000',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    lineHeight: '16px',
    textAlign: 'center',
    textTransform: 'none',

    '&:disabled': {
      cursor: 'default',
      color: '#d8dde6',
    },
  },
});

function getButtonStyling(props: IProps) {
  if (props.disabled) {
    if (props.customStyles) {
      return {
        ...props.styles?.button,
        ...props.styles?.disabled,
        ...props.customStyles,
        ...props.customStyles?.disabled,
      };
    } else {
      return {
        ...props.styles?.button,
        ...props.styles?.disabled,
        ...props.customStyles,
      };
    }
  } else {
    return {
      ...props.styles?.button,
      ...props.styles?.active,
      ...props.customStyles,
    };
  }
}
interface IProps {
  text: string;
  disabled?: boolean;
  styles?: { [key: string]: IExtendedCSSProperties };
  customStyles?: { [key: string]: IExtendedCSSProperties };
  onClick?: (event: React.MouseEvent) => void;
  classes?: ClassNameMap;
}

const DefaultButton: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <Button
      onClick={props.onClick}
      disabled={props.disabled}
      className={clsx(classes.button, props.classes?.button)}
      style={getButtonStyling(props)}>
      {props.text}
    </Button>
  );
};

const ComposedButton: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('Button'),
)(DefaultButton);

export default ComposedButton;
