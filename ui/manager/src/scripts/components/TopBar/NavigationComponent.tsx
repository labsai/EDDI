import * as React from 'react';
import * as Radium from 'radium';
import { CSSProperties } from 'react';

const styles: CSSProperties = {
  navBar: {
    width: '220px',
  },
  navBarItem: {
    ':hover': {
      backgroundColor: '#F7F9FB',
    },
    borderBottom: '3px solid #4A90E2',
    color: '#16325C',
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: '14px',
    height: '39px',
    textAlign: 'center',
    width: '100px',
    lineHeight: '42px',
  },
  navBarItemDisabled: {
    borderBottomColor: '#E0E5EE',
    color: '#7A849E',
  },
  packagesNavRoute: {
    color: '#16325C',
    fontSize: '18px',
    textAlign: 'center',
  },
};

const NavigationComponent = () => (
  <div style={styles.navBar}>
    <div style={styles.navBarItem}>
      <div>{'Bots'}</div>
    </div>
    <div
      key={'packages'}
      style={[styles.navBarItem, styles.navBarItemDisabled]}>
      <div>{'Packages'}</div>
    </div>
  </div>
);

export default Radium(NavigationComponent);
