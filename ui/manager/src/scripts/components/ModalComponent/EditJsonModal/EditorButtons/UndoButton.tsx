import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import EditorButton from './EditorButton';
import * as Radium from 'radium';
import { BLUE_COLOR } from '../../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  button: {},
  icon: {
    top: '-2px',
    left: '2px',
    fontSize: '19px',
    fontWeight: 'bold',
    color: BLUE_COLOR,
    transform: 'rotate(-45deg)',
  },
};

interface IProps {
  text: string;
  customStyles: {};
  onClick(): void;
}

const UndoButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <EditorButton
    text={props.text}
    onClick={props.onClick}
    styles={styles}
    customStyles={props.customStyles}
    icon={'\u21ba'}
  />
);

const ComposedUndoButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('UndoButton'),
)(UndoButton);

export default ComposedUndoButton;
