import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import * as Radium from 'radium';

const styles: CSSProperties = {
  container: {
    ':focus': {
      color: '#FF5976',
      border: '1px solid #FF5976',
    },
    ':hover': {
      color: '#FF5976',
      border: '1px solid #FF5976',
    },
    backgroundColor: '#FFF',
    color: '#D8DDE6',
    cursor: 'pointer',
    fontSize: '25px',
    position: 'relative',
    border: '1px solid #D8DDE6',
    borderRadius: '4px',
    height: '23px',
    width: '23px',
  },
  text: {
    width: '100%',
    position: 'absolute',
    textAlign: 'center',
    paddingTop: '100%',
    transform: 'translateY(-50%)',
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
