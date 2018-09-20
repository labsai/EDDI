import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Link, browserHistory } from 'react-router-dom';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Package from '../AddPackagesModal/Package';
import { IBot, IPackage } from '../../utils/AxiosFunctions';
import {
  packagesSelector,
  packagesWithPluginSelector,
} from '../../../selectors/PackageSelectors';
import { connect } from 'react-redux';
import * as _ from 'lodash';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import Parser from '../../utils/Parser';
import styles from '../AddPackagesModal/AddPackagesModal.styles';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { ClimbingBoxLoader } from 'react-spinners';
import SelectableConfig from './SelectableConfig';

interface IState {
  selectedPackages: string[];
}

interface IPublicProps {
  pluginResource: string;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  packages: IPackage[];
}

class UpdatePackagesModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPackages: [],
    };
  }

  componentDidMount() {
    eddiApiActionDispatchers.fetchPackagesAction();
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  updateSelectedPackages = () => {
    const selectedPackagesForUpdate = this.props.packages.filter(pkg =>
      this.state.selectedPackages.includes(pkg.resource),
    );
    eddiApiActionDispatchers.updatePackagesAction(
      this.props.pluginResource,
      selectedPackagesForUpdate,
    );
    this.closeModal();
  };

  selectPackage = (packageResource: string) => {
    if (this.state.selectedPackages.includes(packageResource)) {
      this.setState({
        selectedPackages: this.state.selectedPackages.filter(
          pack => pack !== packageResource,
        ),
      });
    } else {
      this.setState({
        selectedPackages: this.state.selectedPackages.concat(packageResource),
      });
    }
  };

  isPackageSelected(packageResource: string): boolean {
    return !!this.state.selectedPackages.find(
      selectedPackage =>
        Parser.getId(packageResource) === Parser.getId(selectedPackage),
    );
  }

  render() {
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div
              style={
                styles.title
              }>{`Select packages to update any old versions of the extension to latest`}</div>
            <div style={styles.centerFlex} />
            <BlueButton
              customStyles={styles.button}
              onClick={this.updateSelectedPackages}
              text={'Update selected'}
            />
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.centerFlex} />
            <div style={styles.lastModified}>{'Last modified'}</div>
          </div>
        </div>
        <div>
          {renderIf(!this.props.isLoading && !_.isEmpty(this.props.packages))(
            () => (
              <div style={styles.packageList}>
                {this.props.packages.map((pack, i) => (
                  <SelectableConfig
                    key={i}
                    selected={this.isPackageSelected(pack.resource)}
                    descriptor={pack}
                    handleClick={this.selectPackage}
                  />
                ))}
              </div>
            ),
          )}
          {renderIf(this.props.isLoading)(() => (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          ))}
          {renderIf(!this.props.isLoading && !_.isEmpty(this.props.packages))(
            () => <div>{'Found no packages that can be updated'}</div>,
          )}
        </div>
      </div>
    );
  }
}
const ComposedUpdatePackagesModal: Component<IPrivateProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('UpdatePackagesModal'),
  connect(packagesWithPluginSelector),
)(UpdatePackagesModal);

export default ComposedUpdatePackagesModal;
