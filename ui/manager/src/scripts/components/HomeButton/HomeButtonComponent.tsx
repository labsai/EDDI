import * as React from 'react';
import Radium from 'radium';
import { compose, pure, setDisplayName } from 'recompose';
import styles from './HomeButton.styles';
import { historyPush } from '../../history';

interface IPublic {
  extraPath?: string;
}

interface IProps extends IPublic {}

const HomeButton = (props: IProps) => (
  <div style={styles.navigationBar}>
    <div
      onClick={() => historyPush(`/${props.extraPath || ''}`)}
      style={styles.homeButton}>
      <div style={styles.homeArrow}> </div>
      <div style={styles.homeSquare}> </div>
      <div style={styles.homeText}>{'Home'}</div>
    </div>
    <div style={styles.navigationBarRightSide} />
  </div>
);

const ComposedHomeButton: React.ComponentClass<IProps> = compose<
  IProps,
  IPublic
>(
  pure,
  Radium,
  setDisplayName('HomeButton'),
)(HomeButton);

export default ComposedHomeButton;
