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
import {
  latestPackageSelector,
  specificPackageSelector,
} from '../../selectors/PackageSelectors';
import { PACKAGE, PACKAGE_PATH } from '../utils/EddiTypes';

interface IProps {
  packageId: string;
  version: string;
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

class PackageInfo extends React.Component<IProps> {
  async componentDidMount() {
    if (_.isEmpty(this.props.version)) {
      eddiApiActionDispatchers.fetchCurrentPackageAction(this.props.packageId);
    } else {
      console.log(
        `${PACKAGE}${PACKAGE_PATH}${this.props.packageId}?version=${
          this.props.version
        }`,
      );
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
        <HomeButtonComponent />
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

const ComposedPackageInfo: Component<IProps> = compose<IProps>(
  pure,
  connect(specificPackageSelector),
  setDisplayName('PackageInfo'),
)(PackageInfo);

export default ComposedPackageInfo;
