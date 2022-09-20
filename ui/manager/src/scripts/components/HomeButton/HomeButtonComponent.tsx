import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import useStyles from './HomeButton.styles';
import { historyPush } from '../../history';
import { MANAGE } from '../../constants/paths';

interface IPublic {
  extraPath?: string;
}

interface IProps extends IPublic {}

const HomeButton = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.navigationBar}>
      <div
        onClick={() => historyPush(`${MANAGE}/${props.extraPath || ''}`)}
        className={classes.homeButton}>
        <div className={classes.homeArrow}> </div>
        <div className={classes.homeSquare}> </div>
        <div className={classes.homeText}>{'Home'}</div>
      </div>
      <div className={classes.navigationBarRightSide} />
    </div>
  );
};

const ComposedHomeButton: React.ComponentClass<IProps> = compose<
  IProps,
  IPublic
>(
  pure,
  setDisplayName('HomeButton'),
)(HomeButton);

export default ComposedHomeButton;
