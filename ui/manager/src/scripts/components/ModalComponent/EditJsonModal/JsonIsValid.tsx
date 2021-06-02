import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import * as Radium from 'radium';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import {
  GREEN_COLOR,
  LIGHT_GREY_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const styles: { [key: string]: IExtendedCSSProperties } = {
  content: {
    display: 'flex',
    color: GREEN_COLOR,
    paddingTop: '10px',
    paddingBottom: '10px',
  },
  icon: {
    marginLeft: '20px',
    height: '26px',
    width: '26px',
  },
  text: {
    marginLeft: '4px',
    marginTop: '2px',
    fontSize: '18px',
  },
};

const JsonIsValid: React.StatelessComponent = () => (
  <div style={styles.content}>
    <FontAwesomeIcon
      style={styles.icon}
      icon={['fas', 'check-circle']}
      color={GREEN_COLOR}
    />
    <div style={styles.text}>{'Found no errors. JSON is valid.'}</div>
  </div>
);

interface IProps {
}

const ComposedJsonIsValid: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  Radium,
  setDisplayName('JsonIsValid'),
)(JsonIsValid);

export default ComposedJsonIsValid;
