import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import styles from './EditorButton.styles';
import { CSSProperties } from 'react';
import {
  GREEN_COLOR,
  LIGHT_GREY_COLOR,
} from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

interface IProps {
  customStyles?: { [key: string]: React.CSSProperties };
  onClick(): void;
}

const ValidateButton: React.StatelessComponent<IProps> = (props: IProps) => (
  <div
    onClick={props.onClick}
    style={{ ...styles.validateButton, ...props.customStyles }}>
    <FontAwesomeIcon
      style={styles.icon}
      icon={['fas', 'check-circle']}
      color={LIGHT_GREY_COLOR}
    />
    <div>{'Validate'}</div>
  </div>
);

const ComposedValidateButton: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('ValidateButton'),
)(ValidateButton);

export default ComposedValidateButton;
