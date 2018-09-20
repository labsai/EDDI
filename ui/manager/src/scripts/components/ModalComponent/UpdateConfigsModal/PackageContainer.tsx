import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import Parser from '../utils/Parser';
import Package from './Package';

interface IProps {
  packageResource: string;
}

class PackageContainer extends React.Component<IProps> {
  render() {
    return <div />;
  }
}

const ComposedPackageContainer: Component<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('PackageContainer'),
)(PackageContainer);

export default ComposedPackageContainer;
