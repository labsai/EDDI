import * as React from 'react';
import * as Radium from 'radium';
import { Link } from 'react-router-dom';
import { Component, compose, pure, setDisplayName } from 'recompose';
import styles from './HomeButton.styles';

interface IProps {}

const HomeButton: React.StatelessComponent<IProps> = () => (
  <div style={styles.navigationBar}>
    <Link to="/" style={styles.homeButton}>
      <div style={styles.homeArrow}> </div>
      <div style={styles.homeSquare}> </div>
      <div style={styles.homeText}>{'Home'}</div>
    </Link>
    <div style={styles.navigationBarRightSide} />
  </div>
);

const ComposedHomeButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('HomeButton'),
)(HomeButton);

export default ComposedHomeButton;
