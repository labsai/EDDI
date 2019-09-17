import * as React from 'react';
import PackageView from './PackageView';
import { IPackage } from '../utils/AxiosFunctions';
import { Component, compose, pure, setDisplayName } from 'recompose';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import { connect } from 'react-redux';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import styles from '../Bots/Botlist.styles';
import * as _ from 'lodash';
import { ClimbingBoxLoader } from 'react-spinners';
import * as renderIf from 'render-if';
import { specificPackageSelector } from '../../selectors/PackageSelectors';
import { PACKAGE, PACKAGE_PATH } from '../utils/EddiTypes';

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
        `${PACKAGE}${PACKAGE_PATH}/${this.props.packageId}?version=${
          this.props.version
        }`,
      );
    }
  }

  render() {
    return (
      <div>
        <HomeButtonComponent extraPath={'packages'} />
        {renderIf(this.props.isLoading)(() => (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        ))}
        {renderIf(!this.props.isLoading)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load package'}</p>
            ))}
            {renderIf(
              !this.props.error && _.isEmpty(this.props.packagePayload),
            )(() => <p>{'Package not found'}</p>)}
            {renderIf(
              !this.props.error && !_.isEmpty(this.props.packagePayload),
            )(() => <PackageView packagePayload={this.props.packagePayload} />)}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedPackageInfo: Component<IPrivateProps> = compose<IPrivateProps>(
  pure,
  connect(specificPackageSelector),
  setDisplayName('PackageInfo'),
)(PackageInfo);

export default ComposedPackageInfo;
