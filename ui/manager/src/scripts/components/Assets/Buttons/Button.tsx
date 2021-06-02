import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import * as Radium from 'radium';

const styles: { [key: string]: IExtendedCSSProperties } = {
  button: {
    height: '35px',
    width: '108px',
    border: '1px solid #000',
    backgroundColor: '#CCC',
    color: '#000',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    textAlign: 'center',
  },
  disabled: {
    cursor: 'default',
  },
};
function getButtonStyling(props: IProps) {
  if (props.disabled) {
    if (props.customStyles) {
      return {
        ...styles.button,
        ...styles.disabled,
        ...props.styles.button,
        ...props.customStyles,
        ...props.styles.disabled,
        ...props.customStyles.disabled,
      };
    } else {
      return {
        ...styles.button,
        ...styles.disabled,
        ...props.styles.button,
        ...props.customStyles,
        ...props.styles.disabled,
      };
    }
  } else {
    return {
      ...styles.button,
      ...props.styles.button,
      ...props.styles.active,
      ...props.customStyles,
    };
  }
}
interface IProps {
  text: string;
  disabled?: boolean;
  styles: { [key: string]: IExtendedCSSProperties };
  customStyles: { [key: string]: IExtendedCSSProperties };
  onClick?: (event: React.MouseEvent) => void;
}

const Button: React.StatelessComponent<IProps> = (props: IProps) => (
  <button
    onClick={props.onClick}
    disabled={props.disabled}
    style={getButtonStyling(props)}>
    {props.text}
  </button>
);

const ComposedButton: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('Button'),
)(Button);

export default ComposedButton;
