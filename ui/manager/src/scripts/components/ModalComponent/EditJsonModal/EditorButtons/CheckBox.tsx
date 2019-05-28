import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import EditorButton from './EditorButton';
import * as Radium from 'radium';
import { LIGHT_GREY_COLOR } from '../../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  button: {},
  icon: {
    fontSize: '21px',
    top: '-4px',
    left: '2px',
  },
};

interface IProps {
  text: string;
  checked: boolean;
  customStyles: {};
  onClick(): void;
}

const CheckBox: React.StatelessComponent<IProps> = (props: IProps) => (
  <EditorButton
    text={props.text}
    onClick={props.onClick}
    styles={styles}
    customStyles={props.customStyles}
    icon={props.checked ? '\u2714' : ''}
  />
);

const ComposedCheckBox: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('CheckBox'),
)(CheckBox);

export default ComposedCheckBox;
