import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import { BLUE_COLOR } from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import styles from './EditorButton.styles';
import { CSSProperties } from 'react';

interface IProps {
  customStyles: CSSProperties;
  onClick(): void;
}

const UndoButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <div
    onClick={props.onClick}
    style={{ ...styles.button, ...props.customStyles }}>
    <FontAwesomeIcon
      style={styles.icon}
      icon={['fas', 'undo']}
      color={BLUE_COLOR}
    />
  </div>
);

const ComposedUndoButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('UndoButton'),
)(UndoButton);

export default ComposedUndoButton;
