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
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { ClimbingBoxLoader } from 'react-spinners';
import SelectableConfig from './SelectableConfig';
import { botsWithPackageSelector } from '../../../selectors/BotSelectors';

interface IBotToUpdate {
  botResource: string;
  packageResources: string[];
}

interface IState {
  selectedBots: IBotToUpdate[];
  page: number;
}

interface IPublicProps {
  packageResources: string[];
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  botLists: [IBot[]];
}

class UpdateBotsModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedBots: [],
      page: 0,
    };
  }

  componentDidMount() {
    eddiApiActionDispatchers.fetchBotsAction(10, 0);
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  updateSelectedBots = () => {
    eddiApiActionDispatchers.updateBotsAction(this.state.selectedBots);
    this.closeModal();
  };

  nextPage = () => {
    this.setState({
      page: this.state.page + 1,
    });
  };

  previousPage = () => {
    this.setState({
      page: this.state.page - 1,
    });
  };

  selectBot = (botResource: string) => {
    const currentPakageResource = this.props.packageResources[this.state.page];
    const selectedBot = this.state.selectedBots.find(
      bot => bot.botResource === botResource,
    );
    if (_.isEmpty(selectedBot)) {
      const newList = this.state.selectedBots.map(bot => bot);
      newList.push({
        botResource: botResource,
        packageResources: [currentPakageResource],
      });
      this.setState({ selectedBots: newList });
    } else {
      const newList = this.state.selectedBots.filter(
        bot => bot.botResource !== botResource,
      );
      if (selectedBot.packageResources.includes(currentPakageResource)) {
        newList.push({
          botResource: selectedBot.botResource,
          packageResources: selectedBot.packageResources.filter(
            resource => resource !== currentPakageResource,
          ),
        });
        this.setState({ selectedBots: newList });
      } else {
        selectedBot.packageResources.push(currentPakageResource);
        newList.push(selectedBot);
        this.setState({ selectedBots: newList });
      }
    }
  };

  isBotSelected(botResource: string): boolean {
    return !!this.state.selectedBots.find(
      selectedBot =>
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
            {renderIf(this.state.page > 0)(() => (
              <WhiteButton
                customStyles={styles.backButton}
                onClick={this.previousPage}
                text={'Back'}
              />
            ))}
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
          {renderIf(!this.props.isLoading && !_.isEmpty(this.props.botLists))(
            () => (
              <div style={styles.packageList}>
                {this.props.botLists[this.state.page].map((bot, i) => (
                  <SelectableConfig
                    key={i}
                    selected={this.isBotSelected(bot.resource)}
                    descriptor={bot}
                    handleClick={this.selectBot}
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
          {renderIf(!this.props.isLoading && _.isEmpty(this.props.botLists))(
            () => <div>{'Found no bots that can be updated'}</div>,
          )}
        </div>
      </div>
    );
  }
}
const ComposedUpdateBotsModal: Component<IPrivateProps> = compose<
  IPrivateProps,
  IPublicProps
>(pure, setDisplayName('UpdateBotsModal'), connect(botsWithPackageSelector))(
  UpdateBotsModal,
);

export default ComposedUpdateBotsModal;
