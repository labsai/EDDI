import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { specificPackageSelector } from '../../selectors/PackageSelectors';
import styles from '../Bots/Botlist.styles';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import { IPackage } from '../utils/AxiosFunctions';
import { PACKAGE, PACKAGE_PATH } from '../utils/EddiTypes';
import PackageView from './PackageView';

interface IPublicProps {
  packageId: string;
  version: string;
}

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

class PackageInfo extends React.Component<IPrivateProps> {
  async componentDidMount() {
    if (_.isEmpty(this.props.version)) {
      eddiApiActionDispatchers.fetchCurrentPackageAction(this.props.packageId);
    } else {
      eddiApiActionDispatchers.fetchPackageAction(
        `${PACKAGE}${PACKAGE_PATH}/${this.props.packageId}?version=${this.props.version}`,
      );
    }
  }

  render() {
    return (
      <div>
        <HomeButtonComponent />
        {this.props.isLoading && (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!this.props.isLoading && (
          <div>
            {!!this.props.error && <p>{'Error: Could not load package'}</p>}
            {!this.props.error && _.isEmpty(this.props.packagePayload) && (
              <p>{'Package not found'}</p>
            )}
            {!this.props.error && !_.isEmpty(this.props.packagePayload) && (
              <PackageView packagePayload={this.props.packagePayload} />
            )}
          </div>
        )}
      </div>
    );
  }
}

const ComposedPackageInfo: React.ComponentClass<IPublicProps, IPrivateProps> =
  compose<IPublicProps, IPrivateProps>(
    pure,
    connect(specificPackageSelector),
    setDisplayName('PackageInfo'),
  )(PackageInfo);

export default ComposedPackageInfo;
