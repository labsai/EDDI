import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { packagesWithPluginSelector } from '../../../selectors/PackageSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { getPackagesUsingPlugin, IPackage } from '../../utils/AxiosFunctions';
import Parser from '../../utils/Parser';
import styles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import SelectableConfig from './SelectableConfig';

interface IState {
  selectedPackages: string[];
  packages: IPackage[];
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
      packages: null,
    };
  }

  componentDidMount() {
    this.loadPackagesUsingPlugin();
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  updateSelectedPackages = () => {
    eddiApiActionDispatchers.updatePackagesAction(
      this.props.pluginResource,
      this.state.selectedPackages,
    );
    this.closeModal();
  };

  async loadPackagesUsingPlugin() {
    eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
      this.props.pluginResource,
      true,
    );
    const packages: IPackage[] = await getPackagesUsingPlugin(
      this.props.pluginResource,
      true,
    );
    this.setState({ packages });
  }

  selectPackage = (packageResource: string) => {
    if (this.state.selectedPackages.includes(packageResource)) {
      this.setState({
        selectedPackages: this.state.selectedPackages.filter(
          (pack) => pack !== packageResource,
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
      (selectedPackage) =>
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
          {!_.isEmpty(this.state.packages) && (
            <div style={styles.packageList}>
              {this.state.packages.map((pack, i) => (
                <SelectableConfig
                  key={i}
                  selected={this.isPackageSelected(pack.resource)}
                  descriptor={pack}
                  handleClick={this.selectPackage}
                />
              ))}
            </div>
          )}
          {!this.state.packages && (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          )}
          {!!this.state.packages && _.isEmpty(this.state.packages) && (
            <div>{'Found no packages that can be updated'}</div>
          )}
        </div>
      </div>
    );
  }
}
const ComposedUpdatePackagesModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('UpdatePackagesModal'),
  connect(packagesWithPluginSelector),
)(UpdatePackagesModal);

export default ComposedUpdatePackagesModal;
