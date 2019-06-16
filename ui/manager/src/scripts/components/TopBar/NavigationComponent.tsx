import * as React from 'react';
import * as Radium from 'radium';
import { CSSProperties } from 'react';
import { historyPush } from '../../history';
import PluginSelectComponent from './PluginSelectComponent';
import { REGULAR_DICTIONARY } from '../utils/EddiTypes';

const styles: CSSProperties = {
  navBar: {
    width: 'fit-content',
    display: 'flex',
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
};

export enum pageEnum {
  'dictionary',
  'behavior',
  'output',
  'httpCalls',
  'bot',
  'package',
}

interface IProps {
  page: pageEnum;
}

const NavigationComponent = (props: IProps) => (
  <div style={styles.navBar}>
    <div
      onClick={() => historyPush('/')}
      style={
        props.page === pageEnum.bot
          ? styles.navBarItem
          : [styles.navBarItem, styles.navBarItemDisabled]
      }>
      <div>{'Bots'}</div>
    </div>
    <div
      key={'packages'}
      onClick={() => historyPush('/packages')}
      style={
        props.page === pageEnum.package
          ? styles.navBarItem
          : [styles.navBarItem, styles.navBarItemDisabled]
      }>
      <div>{'Packages'}</div>
    </div>
    <PluginSelectComponent page={props.page} />
  </div>
);

export default Radium(NavigationComponent);
