import { makeStyles } from '@material-ui/core/styles';
import clsx from 'clsx';
import * as React from 'react';
import {
  BLUE_COLOR2,
  DARK_GREY_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR2,
  LIGHT_BLUE_COLOR3,
  SMALL_FONT2,
} from '../../../styles/DefaultStylingProperties';
import { historyPush } from '../../history';
import { pageEnum } from '../pages/pageEnum';
import PluginSelectComponent from './PluginSelectComponent';

const useStyles = makeStyles({
  navBar: {
    width: 'fit-content',
    display: 'flex',
  },
  navBarItem: {
    borderBottom: `3px solid ${BLUE_COLOR2}`,
    color: DARK_GREY_COLOR,
    cursor: 'pointer',
    display: 'inline-block',
    fontSize: SMALL_FONT2,
    height: '39px',
    textAlign: 'center',
    width: '110px',
    lineHeight: '42px',
    '&:hover': {
      backgroundColor: LIGHT_BLUE_COLOR3,
      borderBottom: '3px solid ' + GREY_COLOR,
    },
  },
  navBarItemDisabled: {
    borderBottom: '3px solid ' + LIGHT_GREY_COLOR2,
    color: GREY_COLOR,
  },
});

interface IProps {
  page: pageEnum;
}

const NavigationComponent = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.navBar}>
      <div
        key={'bots'}
        onClick={() => historyPush('/')}
        className={clsx(classes.navBarItem, {
          [classes.navBarItemDisabled]: props.page !== pageEnum.bot,
        })}>
        <div>{'Bots'}</div>
      </div>
      <div
        key={'packages'}
        onClick={() => historyPush('/packages')}
        className={clsx(classes.navBarItem, {
          [classes.navBarItemDisabled]: props.page !== pageEnum.package,
        })}>
        <div>{'Packages'}</div>
      </div>
      <div
        key={'conversations'}
        onClick={() => historyPush('/conversations')}
        className={clsx(classes.navBarItem, {
          [classes.navBarItemDisabled]: props.page !== pageEnum.conversation,
        })}>
        {'Conversations'}
      </div>
      <PluginSelectComponent page={props.page} />
    </div>
  );
};

export default NavigationComponent;
