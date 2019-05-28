import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import EditorButton from './EditorButton';
import * as Radium from 'radium';
import { LIGHT_GREY_COLOR } from '../../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  button: {},
  icon: {
    position: 'relative',
    fontSize: '24px',
    color: LIGHT_GREY_COLOR,
    transform: 'rotate(90deg)',
    top: '-5px',
  },
  expanded: {
    transform: 'rotate(-90deg)',
    top: '-5px',
  },
};

interface IProps {
  text: string;
  expanded: boolean;
  customStyles: {};
  onClick(): void;
}

const ExpandButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <EditorButton
    text={props.text}
    onClick={props.onClick}
    styles={
      props.expanded ? { icon: { ...styles.icon, ...styles.expanded } } : styles
    }
    customStyles={props.customStyles}
    icon={'\u21F1'}
  />
);

const ComposedExpandButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('ExpandButton'),
)(ExpandButton);

export default ComposedExpandButton;
