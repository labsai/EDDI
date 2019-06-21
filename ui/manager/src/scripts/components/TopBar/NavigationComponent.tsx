import * as React from 'react';
import * as Radium from 'radium';
import { CSSProperties } from 'react';
import { historyPush } from '../../history';
import PluginSelectComponent from './PluginSelectComponent';
import { REGULAR_DICTIONARY } from '../utils/EddiTypes';
import {
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_GREY_COLOR3,
  SMALL_FONT2,
} from '../../../styles/DefaultStylingProperties';
import { pageEnum } from '../pages/ExtensionsPage';

const styles: CSSProperties = {
  navBar: {
    width: 'fit-content',
    display: 'flex',
  },
  navBarItem: {
    ':hover': {
      backgroundColor: LIGHT_GREY_COLOR3,
      borderBottom: '3px solid ' + GREY_COLOR,
    },
    borderBottom: '3px solid #4A90E2',
    color: DARK_BLUE_COLOR,
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: SMALL_FONT2,
    height: '39px',
    textAlign: 'center',
    width: '100px',
    lineHeight: '42px',
  },
  navBarItemDisabled: {
    borderBottom: '3px solid ' + LIGHT_GREY_COLOR2,
    color: GREY_COLOR,
  },
};

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
