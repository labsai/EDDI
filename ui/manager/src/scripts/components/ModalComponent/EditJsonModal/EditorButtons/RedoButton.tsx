import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import * as Radium from 'radium';
import { BLUE_COLOR } from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import styles from './EditorButton.styles';

interface IProps {
  customStyles: CSSProperties;
  onClick(): void;
}

const RedoButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <div
    onClick={props.onClick}
    style={{ ...styles.button, ...props.customStyles }}>
    <FontAwesomeIcon
      style={styles.icon}
      icon={['fas', 'redo']}
      color={BLUE_COLOR}
    />
  </div>
);

const ComposedRedoButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('RedoButton'),
)(RedoButton);

export default ComposedRedoButton;
