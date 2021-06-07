import * as _ from 'lodash';
import Radium from 'radium';
import * as React from 'react';
import { connect } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { historyPush } from '../../history';
import { packageSelector } from '../../selectors/PackageSelectors';
import Options from '../Assets/Buttons/Options';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import { IPackage } from '../utils/AxiosFunctions';
import styles from './Package.styles';
import PluginList from './PluginList';

interface IPublicProps {
  isPackageInBot: boolean;
  packageResource: string;
  selectVersion(version: number): void;
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
  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.fetchPlugins();
    }
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
    this.props.selectVersion(newVersion);
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
        {this.props.isLoading && _.isEmpty(packagePayload) && (
          <p>{'Loading package'}</p>
        )}
        <div>
          {!!this.props.error && <p>{'Error: Could not load package'}</p>}
          {!this.props.error && !_.isEmpty(packagePayload) && (
            <div style={styles.pack}>
              <div
                style={styles.packageHeader}
                onClick={() =>
                  historyPush(
                    `/packageview/${this.props.packagePayload.id}`,
                    isCurrentVersion
                      ? null
                      : [`version=${this.props.packagePayload.version}`],
                  )
                }>
                <div style={this.getNameStyle()}>
                  {packagePayload.name || packagePayload.id}
                </div>
                <div
                  onClick={(e) => e.stopPropagation()}
                  style={styles.version}>
                  <VersionSelectComponent
                    selectedVersion={packagePayload.version}
                    currentVersion={packagePayload.currentVersion}
                    selectVersion={this.selectVersion}
                  />
                </div>
                {!_.isEmpty(packagePayload.updatablePlugins) && (
                  <div style={styles.warning}>
                    <img src={warningIcon} style={styles.warningIcon} />
                    <div style={styles.updateAvailable}>
                      {'Updates Available'}
                    </div>
                  </div>
                )}
                <div style={styles.centerFlex} />
                <div
                  style={styles.options}
                  onClick={(e) => e.stopPropagation()}>
                  <Options
                    descriptor={packagePayload}
                    data={JSON.stringify(
                      packagePayload.packageData,
                      null,
                      '\t',
                    )}
                  />
                </div>
                <button
                  disabled={!isCurrentVersion}
                  style={this.getEditPackageStyle()}
                  onClick={() =>
                    historyPush(`/packageview/${packagePayload.id}`)
                  }>
                  {'View package'}
                </button>
              </div>
              {_.isUndefined(packagePayload.packageData) && (
                <ClipLoader color={BLUE_COLOR} />
              )}
              <div style={styles.packageContent}>
                <PluginList packagePayload={packagePayload} />
              </div>
            </div>
          )}
        </div>
      </div>
    );
  }
}

const ComposedPackage: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(packageSelector),
  Radium,
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
