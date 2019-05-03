import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import { IPackage } from '../../utils/AxiosFunctions';
import { connect } from 'react-redux';
import NameAndVersion from './NameAndVersion';
import { packageSelector } from '../../../selectors/PackageSelectors';
import { historyPush } from '../../../history';

interface IPublicProps {
  packageResource: string;
  usedByOlderVersion: boolean;
  isSmallName: boolean;
}

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

class Package extends React.Component<IPrivateProps> {
  render() {
    return (
      <NameAndVersion
        descriptor={this.props.packagePayload}
        usedByOlderVersion={this.props.usedByOlderVersion}
        isSmallName={this.props.isSmallName}
        onClick={() =>
          historyPush(`/packageview/${this.props.packagePayload.id}`)
        }
      />
    );
  }
}

const ComposedPackage: Component<IProps> = compose<IProps>(
  pure,
  connect(packageSelector),
  Radium,
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
