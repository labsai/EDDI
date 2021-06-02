import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Radium from 'radium';
import { LIGHT_GREY_COLOR } from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import styles from './EditorButton.styles';

interface IProps {
  expanded: boolean;
  customStyles?: CSSProperties;
  onClick(): void;
}

const ExpandButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <div
    onClick={props.onClick}
    style={{ ...styles.button, ...props.customStyles }}>
    <FontAwesomeIcon
      style={styles.icon}
      icon={['fas', props.expanded ? 'compress' : 'expand']}
      color={LIGHT_GREY_COLOR}
    />
  </div>
);

const ComposedExpandButton: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('ExpandButton'),
)(ExpandButton);

export default ComposedExpandButton;
