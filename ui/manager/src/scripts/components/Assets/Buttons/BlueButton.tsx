import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Button from './Button';
import * as Radium from 'radium';

const styles: CSSProperties = {
  button: {
    border: '0px',
    color: '#FFFFFF',
    backgroundColor: '#0070D2',
  },
  disabled: {
    backgroundColor: '#c4c9d2',
    cursor: 'default',
  },
  active: {
    ':hover': {
      backgroundColor: '#4A90E2',
    },
    ':active': {
      backgroundColor: '#0070D2',
    },
  },
};

interface IProps {
  text: string;
  disabled: boolean;
  customStyles: {};
  onClick(): void;
}

const BlueButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <Button
    text={props.text}
    onClick={props.onClick}
    disabled={props.disabled}
    styles={styles}
    customStyles={props.customStyles}
  />
);

const ComposedBlueButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('BlueButton'),
)(BlueButton);

export default ComposedBlueButton;
