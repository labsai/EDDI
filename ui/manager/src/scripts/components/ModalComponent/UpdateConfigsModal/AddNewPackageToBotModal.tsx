import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { botsSelector } from '../../../selectors/BotSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';
import { IBot, IPackage } from '../../utils/AxiosFunctions';
import styles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import SelectableConfig from './SelectableConfig';

interface IState {
  selectedBots: IBot[];
}

interface IPublicProps {
  packagePayload: IPackage;
}

interface IPrivateProps extends IPublicProps {
  bots: IBot[];
  isLoading: boolean;
  allBotsLoaded: boolean;
  error: Error;
  botsLoaded: number;
}

class AddNewPackageToBotModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedBots: [],
    };
  }

  componentDidMount() {
    if (!this.props.allBotsLoaded && this.props.botsLoaded < DEFAULT_LIMIT) {
      this.loadMore();
    }
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  updateSelectedBots = () => {
    eddiApiActionDispatchers.addNewPackageToBotsAction(
      this.props.packagePayload.resource,
      this.state.selectedBots,
    );
    this.closeModal();
  };

  loadMore = () => {
    if (this.props.bots.length < DEFAULT_LIMIT && !this.props.allBotsLoaded) {
      eddiApiActionDispatchers.fetchBotsAction(DEFAULT_LIMIT, 0);
    } else {
      eddiApiActionDispatchers.fetchBotsAction(
        DEFAULT_LIMIT,
        Math.floor(this.props.botsLoaded / DEFAULT_LIMIT),
      );
    }
  };

  selectBot = (botResource: string) => {
    const newBotList = this.state.selectedBots.map((bot) => bot);
    if (this.isBotSelected(botResource)) {
      this.setState({
        selectedBots: newBotList.filter((bot) => bot.resource !== botResource),
      });
    } else {
      newBotList.push(
        this.props.bots.find((bot) => bot.resource === botResource),
      );
      this.setState({ selectedBots: newBotList });
    }
  };

  isBotSelected(botResource: string): boolean {
    return !_.isEmpty(
      this.state.selectedBots.find((bot) => bot.resource === botResource),
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
              }>{`Select bots you want to add the package to.`}</div>
            <div style={styles.centerFlex} />
            <BlueButton
              onClick={this.updateSelectedBots}
              customStyles={styles.button}
              text={'Update Bots'}
            />
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.centerFlex} />
            <div style={styles.lastModified}>{'Last modified'}</div>
          </div>
        </div>
        <div>
          <div style={styles.packageList}>
            {this.props.bots.map((bot, i) => (
              <SelectableConfig
                key={i}
                selected={this.isBotSelected(bot.resource)}
                descriptor={bot}
                handleClick={this.selectBot}
              />
            ))}
          </div>
          {this.props.isLoading && (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          )}
          {!this.props.allBotsLoaded && !this.props.isLoading && (
            <BlueButton
              customStyles={styles.loadMoreButton}
              onClick={this.loadMore}
              text={'Load More'}
            />
          )}
          {!this.props.isLoading && _.isEmpty(this.props.bots) && (
            <div>{'Found no bots that can be updated'}</div>
          )}
        </div>
      </div>
    );
  }
}

const ComposedAddNewPackageToBotModal: React.ComponentClass<IPublicProps> =
  compose<IPrivateProps, IPublicProps>(
    pure,
    setDisplayName('AddNewPackageToBotModal'),
    connect(botsSelector),
  )(AddNewPackageToBotModal);

export default ComposedAddNewPackageToBotModal;
