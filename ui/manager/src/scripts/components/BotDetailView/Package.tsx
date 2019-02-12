import * as React from 'react';
import * as Radium from 'radium';
import * as renderIf from 'render-if';
import PluginList from './PluginList';
import styles from './Package.styles';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IPackage } from '../utils/AxiosFunctions';
import { connect } from 'react-redux';
import { packageSelector } from '../../selectors/PackageSelectors';
import * as _ from 'lodash';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import { Link, browserHistory } from 'react-router-dom';
import { history } from '../../history';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';

interface IPublicProps {
  isPackageInBot: boolean;
  packageResource: string;
  selectVersion(resource: string, version: number): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  packagePayload: IPackage;
  isLoading: boolean;
}

const warningIcon = require('../../../public/images/WarningIcon.png');

class Package extends React.Component<IPrivateProps> {
  componentDidMount() {
    this.fetchPlugins();
  }
  componentWillReceiveProps(nextProps) {
    this.fetchPlugins(nextProps);
  }

  fetchPlugins(props = this.props) {
    if (
      !_.isUndefined(props.packagePayload) &&
      _.isUndefined(props.packagePayload.packageData)
    ) {
      eddiApiActionDispatchers.fetchPackageDataAction(props.packageResource);
    }
  }

  selectVersion = (newVersion: number) => {
    this.props.selectVersion(this.props.packagePayload.resource, newVersion);
  };

  getNameStyle() {
    if (this.props.isPackageInBot) {
      return {
        ...styles.packageName,
        color: 'red',
      };
    } else {
      return styles.packageName;
    }
  }
  getEditPackageStyle() {
    if (
      this.props.packagePayload.version ===
      this.props.packagePayload.currentVersion
    ) {
      return {
        ...styles.editPackage,
      };
    } else {
      return {
        ...styles.editPackage,
        ...styles.editPackageDisabled,
      };
    }
  }
  render() {
    const { packagePayload } = this.props;
    const isCurrentVersion: boolean =
      !_.isEmpty(packagePayload) &&
      packagePayload.version === packagePayload.currentVersion;
    return (
      <div>
        {renderIf(this.props.isLoading && _.isEmpty(packagePayload))(() => (
          <p>{'Loading package'}</p>
        ))}
        <div>
          {renderIf(this.props.error)(() => (
            <p>{'Error: Could not load package'}</p>
          ))}
          {renderIf(!this.props.error && !_.isEmpty(packagePayload))(() => (
            <div style={styles.pack}>
              <div style={styles.packageHeader}>
                <div style={this.getNameStyle()}>
                  {packagePayload.name || packagePayload.id}
                </div>
                <VersionSelectComponent
                  selectedVersion={packagePayload.version}
                  currentVersion={packagePayload.currentVersion}
                  selectVersion={this.selectVersion}
                />
                {renderIf(!_.isEmpty(packagePayload.updatablePlugins))(() => (
                  <div style={styles.warning}>
                    <img src={warningIcon} style={styles.warningIcon} />
                    <div style={styles.updateAvailable}>
                      {'Updates Available'}
                    </div>
                  </div>
                ))}
                <div style={styles.centerFlex} />
                <button
                  disabled={!isCurrentVersion}
                  style={this.getEditPackageStyle()}
                  onClick={() =>
                    history.push(`/packageview/${packagePayload.id}`)
                  }>
                  {'Edit package'}
                </button>
              </div>
              <div style={styles.packageContent}>
                <PluginList packagePayload={packagePayload} />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedPackage: Component<IPublicProps> = compose<IPublicProps>(
  pure,
  Radium,
  connect(packageSelector),
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
