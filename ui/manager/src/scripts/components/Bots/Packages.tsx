import { IBot, IPackage } from '../utils/AxiosFunctions';
import Package from './Package';
import * as React from 'react';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';

interface IProps {
  packages: string[];
  packHasNewVersion: boolean;
  bot: IBot;
}
const styles: CSSProperties = {
  packageList: {
    display: 'grid',
    flex: 1,
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
    margin: '25px 25px 10px 25px',
  },
};

const Packages: React.StatelessComponent<IProps> = (props: IProps) => {
  return (
    <div style={styles.packageList}>
      {props.packages.map(pack => (
        <Package key={pack} packageResource={pack} bot={props.bot} />
      ))}
    </div>
  );
};

const ComposedPackages: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('Packages'),
)(Packages);

export default ComposedPackages;
