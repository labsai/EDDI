import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Button from './Button';
import Radium from 'radium';

const styles: { [key: string]: IExtendedCSSProperties } = {
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
  disabled?: boolean;
  customStyles?: {};
  onClick(event: React.MouseEvent): void;
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

const ComposedBlueButton: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('BlueButton'),
)(BlueButton);

export default ComposedBlueButton;
