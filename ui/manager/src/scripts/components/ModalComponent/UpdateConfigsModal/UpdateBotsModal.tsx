import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { loadingBotSelector } from '../../../selectors/BotSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { getBotsUsingPackage, IBot } from '../../utils/AxiosFunctions';
import styles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import SelectableConfig from './SelectableConfig';

interface IBotToUpdate {
  botResource: string;
  packageResources: string[];
}

interface IState {
  selectedBots: IBotToUpdate[];
  page: number;
  bots: [IBot[]];
}

interface IPublicProps {
  packageResources: string[];
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
}

class UpdateBotsModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedBots: [],
      page: 0,
      bots: null,
    };
  }

  componentDidMount() {
    this.loadBotsUsingPackage(0);
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  updateSelectedBots = () => {
    eddiApiActionDispatchers.updateBotsAction(this.state.selectedBots);
    this.closeModal();
  };

  async loadBotsUsingPackage(page: number) {
    eddiApiActionDispatchers.fetchBotsUsingPackageAction(
      this.props.packageResources[page],
      true,
    );
    let bots: [IBot[]];
    if (page === 0) {
      bots = [
        await getBotsUsingPackage(this.props.packageResources[page], true),
      ];
    } else {
      bots = { ...this.state.bots };
      bots.push(
        await getBotsUsingPackage(this.props.packageResources[page], true),
      );
    }
    this.setState({ bots });
  }

  nextPage = () => {
    this.setState({
      page: this.state.page + 1,
    });
    if (_.isEmpty(this.state.bots[this.state.page])) {
      this.loadBotsUsingPackage(this.state.page);
    }
  };

  previousPage = () => {
    this.setState({
      page: this.state.page - 1,
    });
  };

  selectBot = (botResource: string) => {
    const currentPackageResource = this.props.packageResources[this.state.page];
    const selectedBot = this.state.selectedBots.find(
      (bot) => bot.botResource === botResource,
    );
    if (_.isEmpty(selectedBot)) {
      const newList = this.state.selectedBots.map((bot) => bot);
      newList.push({
        botResource: botResource,
        packageResources: [currentPackageResource],
      });
      this.setState({ selectedBots: newList });
    } else {
      const newList = this.state.selectedBots.filter(
        (bot) => bot.botResource !== botResource,
      );
      if (selectedBot.packageResources.includes(currentPackageResource)) {
        newList.push({
          botResource: selectedBot.botResource,
          packageResources: selectedBot.packageResources.filter(
            (resource) => resource !== currentPackageResource,
          ),
        });
        this.setState({ selectedBots: newList });
      } else {
        selectedBot.packageResources.push(currentPackageResource);
        newList.push(selectedBot);
        this.setState({ selectedBots: newList });
      }
    }
  };

  isBotSelected(botResource: string): boolean {
    return !!this.state.selectedBots.find(
      (selectedBot) =>
        selectedBot.botResource === botResource &&
        selectedBot.packageResources.includes(
          this.props.packageResources[this.state.page],
        ),
    );
  }

  isLastPage(): boolean {
    return this.state.page === _.size(this.props.packageResources) - 1;
  }

  render() {
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div
              style={
                styles.title
              }>{`Select bots to update any old versions of the package to latest`}</div>
            <div style={styles.centerFlex} />
            {this.state.page > 0 && (
              <WhiteButton
                customStyles={styles.backButton}
                onClick={this.previousPage}
                text={'Back'}
              />
            )}
            <BlueButton
              customStyles={styles.button}
              onClick={
                this.isLastPage() ? this.updateSelectedBots : this.nextPage
              }
              text={this.isLastPage() ? 'Update selected' : 'Next'}
            />
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.centerFlex} />
            <div style={styles.lastModified}>{'Last modified'}</div>
          </div>
        </div>
        <div>
          {!this.props.isLoading &&
            !_.isEmpty(this.state.bots) &&
            !_.isEmpty(this.state.bots[this.state.page]) && (
              <div style={styles.packageList}>
                {this.state.bots[this.state.page].map((bot, i) => (
                  <SelectableConfig
                    key={i}
                    selected={this.isBotSelected(bot.resource)}
                    descriptor={bot}
                    handleClick={this.selectBot}
                  />
                ))}
              </div>
            )}
          {this.props.isLoading && (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          )}
          {!this.props.isLoading && _.isEmpty(this.state.bots) && (
            <div>{'Found no bots that can be updated'}</div>
          )}
        </div>
      </div>
    );
  }
}
const ComposedUpdateBotsModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('UpdateBotsModal'),
  connect(loadingBotSelector),
)(UpdateBotsModal);

export default ComposedUpdateBotsModal;
