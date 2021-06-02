import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Button from './Button';
import * as Radium from 'radium';

const styles: { [key: string]: IExtendedCSSProperties } = {
  button: {
    border: '1px solid #D8DDE6',
    backgroundColor: '#FFFFFF',
    color: '#0070D2',
  },
  disabled: {
    color: '#D8DDE6',
    cursor: 'default',
  },
  active: {
    ':hover': {
      backgroundColor: '#E4E6E9',
    },
    ':active': {
      backgroundColor: '#FFFFFF',
    },
  },
};

interface IProps {
  text: string;
  disabled?: boolean;
  customStyles?: {};
  onClick(): void;
}

const WhiteButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <Button
    text={props.text}
    onClick={props.onClick}
    disabled={props.disabled}
    styles={styles}
    customStyles={props.customStyles}
  />
);

const ComposedWhiteButton: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('WhiteButton'),
)(WhiteButton);

export default ComposedWhiteButton;
