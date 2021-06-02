import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import {
  BLUE_COLOR,
  LIGHT_GREY_COLOR,
} from '../../../../../styles/DefaultStylingProperties';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import styles from './EditorButton.styles';
import * as renderIf from 'render-if';
import { CSSProperties } from 'react';

interface IProps {
  checked: boolean;
  customStyles: { [key: string]: React.CSSProperties };
  onClick(): void;
}

const CheckBox: React.StatelessComponent<IProps> = (props: IProps) => (
  <div
    onClick={props.onClick}
    style={{ ...styles.button, ...props.customStyles }}>
    {renderIf(props.checked)(() => (
      <FontAwesomeIcon
        style={styles.icon}
        icon={['fas', 'check']}
        color={LIGHT_GREY_COLOR}
      />
    ))}
  </div>
);

const ComposedCheckBox: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('CheckBox'),
)(CheckBox);

export default ComposedCheckBox;
