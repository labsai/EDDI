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
  availablePackages: string[];
}

interface IPublicProps {
  bot: IBot;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  packages: IPackage[];
}

class AddPackagesModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPackages: [],
      availablePackages: [],
    };
  }

  componentDidMount() {
    eddiApiActionDispatchers.fetchPackagesAction();
    this.discardChanges();
  }

  componentWillReceiveProps(nextProps) {
    if (
      !_.isEmpty(
        _.differenceBy(nextProps.packages, this.props.packages, 'resource'),
      )
    ) {
      this.discardChanges(nextProps);
    }
  }

  closeModal = () => {
    this.discardChanges();
    ModalActionDispatchers.closeModal();
  };

  selectVersion = (resource: string, version: number) => {
    const id = Parser.getId(resource);
    const availablePackages = this.state.availablePackages.map(pkg => {
      if (Parser.getId(pkg) === id) {
        return Parser.replaceResourceVersion(pkg, version);
      }
      return pkg;
    });
    const selectedPackages = this.state.selectedPackages.filter(
      selectedPackage => Parser.getId(selectedPackage) !== id,
    );
    this.setState({
      availablePackages,
      selectedPackages,
    });
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

  unsavedChanges(): boolean {
    return _.isEqual(this.state.selectedPackages, this.props.bot.packages);
  }

  discardChanges(props = this.props): void {
    const availablePackages = props.packages.map(pkg => {
      return this.getBotPackageIfUsed(pkg.resource);
    });
    this.setState({
      selectedPackages: props.bot.packages,
      availablePackages,
    });
  }

  isPackageSelected(packageResource: string): boolean {
    return !!this.state.selectedPackages.find(
      selectedPackage =>
        Parser.getId(packageResource) === Parser.getId(selectedPackage),
    );
  }

  getBotPackageIfUsed(packageResource: string): string {
    const botPackage = this.props.bot.packages.find(
      botPackage => Parser.getId(packageResource) === Parser.getId(botPackage),
    );
    return botPackage || packageResource;
  }

  updateBot = () => {
    eddiApiActionDispatchers.updateBotPackagesAction(
      this.props.bot,
      this.state.selectedPackages,
    );
    this.closeModal();
  };

  render() {
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div style={styles.title}>{`Select packages for <${
              this.props.bot.name
            }>`}</div>
            <div style={styles.centerFlex} />
            <BlueButton
              customStyles={styles.button}
              disabled={this.unsavedChanges()}
              onClick={this.updateBot}
              text={'Save changes'}
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
              {this.state.availablePackages.map((pack, i) => (
                <Package
                  key={i}
                  selected={this.isPackageSelected(pack)}
                  packageResource={pack}
                  handleClick={this.selectPackage}
                  selectVersion={this.selectVersion}
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
const ComposedAddPackagesModal: Component<IPrivateProps> = compose<
  IPrivateProps
>(pure, setDisplayName('AddPackagesModal'), connect(packagesSelector))(
  AddPackagesModal,
);

export default ComposedAddPackagesModal;
