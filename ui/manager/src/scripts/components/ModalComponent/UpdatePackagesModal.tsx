import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Link, browserHistory } from 'react-router-dom';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Package from './Package';
import { IBot, IPackage } from '../../utils/AxiosFunctions';
import { packagesSelector } from '../../../selectors/PackageSelectors';
import { connect } from 'react-redux';
import * as _ from 'lodash';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import Parser from '../../utils/Parser';
import styles from './AddPackagesModal.styles';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { ClimbingBoxLoader } from 'react-spinners';

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
              }>{`Select packages to update from any old versions of the extension to latest`}</div>
            <div style={styles.centerFlex} />
            <BlueButton
              customStyles={styles.button}
              onClick={this.closeModal}
              text={'Update selected packages'}
            />
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.centerFlex} />
            <div style={styles.lastModified}>{'Last modified'}</div>
          </div>
        </div>
        <div>
          {renderIf(!this.props.isLoading)(() => (
            <div style={styles.packageList}>
              {this.props.packages.map((pack, i) => (
                <Package
                  key={i}
                  selected={this.isPackageSelected(pack)}
                  packageResource={pack}
                  handleClick={this.selectPackage}
                />
              ))}
            </div>
          ))}
          {renderIf(this.props.isLoading)(() => (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          ))}
        </div>
      </div>
    );
  }
}
const ComposedUpdatePackagesModal: Component<IPrivateProps> = compose<
  IPrivateProps
>(pure, setDisplayName('UpdatePackagesModal'), connect(packagesSelector))(
  UpdatePackagesModal,
);

export default ComposedUpdatePackagesModal;
