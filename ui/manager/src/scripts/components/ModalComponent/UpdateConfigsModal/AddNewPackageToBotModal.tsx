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
import useStyles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import SelectableConfig from './SelectableConfig';

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

const AddNewPackageToBotModal = (props: IPrivateProps) => {
  const [selectedBots, setSelectedBots] = React.useState<IBot[]>([]);

  const classes = useStyles();

  React.useEffect(() => {
    if (!props.allBotsLoaded && props.botsLoaded < DEFAULT_LIMIT) {
      loadMore();
    }
  }, []);

  const closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  const updateSelectedBots = () => {
    eddiApiActionDispatchers.addNewPackageToBotsAction(
      props.packagePayload.resource,
      selectedBots,
    );
    closeModal();
  };

  const loadMore = () => {
    if (props.bots.length < DEFAULT_LIMIT && !props.allBotsLoaded) {
      eddiApiActionDispatchers.fetchBotsAction(DEFAULT_LIMIT, 0);
    } else {
      eddiApiActionDispatchers.fetchBotsAction(
        DEFAULT_LIMIT,
        Math.floor(props.botsLoaded / DEFAULT_LIMIT),
      );
    }
  };

  const selectBot = (botResource: string) => {
    const newBotList = selectedBots.map((bot) => bot);
    if (isBotSelected(botResource)) {
      setSelectedBots(newBotList.filter((bot) => bot.resource !== botResource));
    } else {
      newBotList.push(props.bots.find((bot) => bot.resource === botResource));
      setSelectedBots(newBotList);
    }
  };

  const isBotSelected = (botResource: string): boolean => {
    return !_.isEmpty(selectedBots.find((bot) => bot.resource === botResource));
  };

  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div
            className={
              classes.title
            }>{`Select bots you want to add the package to.`}</div>
          <div className={classes.centerFlex} />
          <BlueButton
            onClick={updateSelectedBots}
            classes={{ button: classes.button }}
            text={'Update Bots'}
          />
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.centerFlex} />
          <div className={classes.lastModified}>{'Last modified'}</div>
        </div>
      </div>
      <div>
        <div className={classes.packageList}>
          {props.bots.map((bot, i) => (
            <SelectableConfig
              key={i}
              selected={isBotSelected(bot.resource)}
              descriptor={bot}
              handleClick={selectBot}
            />
          ))}
        </div>
        {props.isLoading && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading color="white" />
          </div>
        )}
        {!props.allBotsLoaded && !props.isLoading && (
          <BlueButton
            classes={{ button: classes.loadMoreButton }}
            onClick={loadMore}
            text={'Load More'}
          />
        )}
        {!props.isLoading && _.isEmpty(props.bots) && (
          <div>{'Found no bots that can be updated'}</div>
        )}
      </div>
    </div>
  );
};

const ComposedAddNewPackageToBotModal: React.ComponentClass<IPublicProps> =
  compose<IPrivateProps, IPublicProps>(
    pure,
    setDisplayName('AddNewPackageToBotModal'),
    connect(botsSelector),
  )(AddNewPackageToBotModal);

export default ComposedAddNewPackageToBotModal;
