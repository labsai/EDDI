import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import * as Radium from 'radium';
import {
  LARGE_FONT2,
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR,
  RED_BORDER,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  container: {
    ':hover': {
      color: RED_COLOR,
      border: RED_BORDER,
    },
    ':focus': {
      color: RED_COLOR,
      border: RED_BORDER,
    },
    backgroundColor: WHITE_COLOR,
    color: LIGHT_GREY_COLOR,
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
};

function getContainerStyling(props: IProps) {
  return {
    ...styles.container,
    ...props.customStyles,
  };
}

interface IProps {
  customStyles: {};
  onClick(): void;
}

const SquareXButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <div onClick={props.onClick} style={getContainerStyling(props)}>
    <div style={styles.text}>&times;</div>
  </div>
);

const ComposedSquareXButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('SquareXButton'),
)(SquareXButton);

export default ComposedSquareXButton;
