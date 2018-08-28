import * as React from 'react';
import PackageView from './PackageView';
import { IPackage } from '../utils/AxiosFunctions';
import { Component, compose, pure, setDisplayName } from 'recompose';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import * as Radium from 'radium';
import { connect } from 'react-redux';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import styles from '../Bots/Botlist.styles';
import * as _ from 'lodash';
import { ClimbingBoxLoader } from 'react-spinners';
import * as renderIf from 'render-if';
import { latestPackageSelector } from '../../selectors/PackageSelectors';

interface IProps {
  packageId: string;
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

class PackageInfo extends React.Component<IProps> {
  async componentDidMount() {
    eddiApiActionDispatchers.fetchCurrentPackageAction(this.props.packageId);
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
  connect(latestPackageSelector),
  setDisplayName('PackageInfo'),
)(PackageInfo);

export default ComposedPackageInfo;
