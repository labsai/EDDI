import { IBot } from '../utils/AxiosFunctions';
import Package from './Package';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { makeStyles } from '@material-ui/core/styles';

interface IProps {
  packages: string[];
  packHasNewVersion?: boolean;
  bot: IBot;
}
const useStyles = makeStyles({
  packageList: {
    display: 'grid',
    flex: 1,
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
    margin: '25px 25px 10px 25px',
  },
});

const Packages: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.packageList}>
      {props.packages.map((pack) => (
        <Package key={pack} packageResource={pack} bot={props.bot} />
      ))}
    </div>
  );
};

const ComposedPackages: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('Packages'),
)(Packages);

export default ComposedPackages;
