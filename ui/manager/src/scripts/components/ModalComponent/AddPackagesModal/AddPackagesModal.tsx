import * as React from 'react';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import PackageContainer from './PackageContainer';
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
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';

interface IState {
  selectedPackages: string[];
  loading: boolean;
}

interface IPublicProps {
  bot: IBot;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  allPackagesLoaded: boolean;
  packages: IPackage[];
  packagesLoaded: number;
}

class AddPackagesModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPackages: [],
      loading: false,
    };
  }

  componentDidMount() {
    if (
      this.props.packagesLoaded < DEFAULT_LIMIT &&
      !this.props.allPackagesLoaded
    ) {
      eddiApiActionDispatchers.fetchPackagesAction(DEFAULT_LIMIT, 0);
    }
    this.discardChanges();
  }

  componentDidUpdate(prevProps) {
    if (prevProps.isLoading && !this.props.isLoading) {
      this.setState({ loading: false });
    }
  }

  closeModal = () => {
    this.discardChanges();
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

  unsavedChanges(): boolean {
    return _.isEqual(this.state.selectedPackages, this.props.bot.packages);
  }

  discardChanges(props = this.props): void {
    this.setState({
      selectedPackages: props.bot.packages,
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

  loadMore = () => {
    const fetchIndex = Math.floor(this.props.packagesLoaded / DEFAULT_LIMIT);
    if (this.state.loading || _.isEmpty(this.props.packages)) {
      return;
    }
    this.setState({ loading: true });
    eddiApiActionDispatchers.fetchPackagesAction(DEFAULT_LIMIT, fetchIndex);
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
        <div style={styles.packageList}>
          <div>
            {this.props.packages.map((pack, i) => (
              <PackageContainer
                key={i}
                packageResource={this.getBotPackageIfUsed(pack.resource)}
                selected={this.isPackageSelected(pack.resource)}
                handleClick={this.selectPackage}
              />
            ))}
          </div>
          {renderIf(this.props.isLoading)(() => (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          ))}
          {renderIf(
            !this.props.allPackagesLoaded &&
              !this.props.isLoading &&
              !this.state.loading,
          )(() => (
            <BlueButton
              customStyles={styles.loadMoreButton}
              onClick={this.loadMore}
              text={'Load More'}
            />
          ))}
        </div>
      </div>
    );
  }
}
const ComposedAddPackagesModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps, IPublicProps
>(pure, setDisplayName('AddPackagesModal'), connect(packagesSelector))(
  AddPackagesModal,
);

export default ComposedAddPackagesModal;
