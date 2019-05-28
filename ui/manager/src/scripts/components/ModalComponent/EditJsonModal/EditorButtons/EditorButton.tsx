import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import * as Radium from 'radium';
import {
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR,
} from '../../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  button: {
    ':hover': {
      backgroundColor: '#666666',
    },
    position: 'relative',
    top: '2px',
    border: LIGHT_GREY_BORDER,
    width: '21px',
    height: '21px',
    fontSize: '21px',
    borderRadius: '3px',
    backgroundColor: '#555555',
    marginLeft: '4px',
    color: LIGHT_GREY_COLOR,
    cursor: 'pointer',
  },
  icon: {
    position: 'relative',
    fontSize: '24px',
    color: LIGHT_GREY_COLOR,
  },
};
function getButtonStyling(props: IProps) {
  return {
    ...styles.button,
    ...props.styles.button,
    ...props.customStyles,
  };
}
function getIconStyling(props: IProps) {
  return {
    ...styles.icon,
    ...props.styles.icon,
  };
}
interface IProps {
  styles: { button; icon };
  customStyles: {};
  icon: string;
  onClick(): void;
}

const EditorButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <div onClick={props.onClick} style={getButtonStyling(props)}>
    <div style={getIconStyling(props)}>{props.icon}</div>
  </div>
);

const ComposedEditorButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('EditorButton'),
)(EditorButton);

export default ComposedEditorButton;
