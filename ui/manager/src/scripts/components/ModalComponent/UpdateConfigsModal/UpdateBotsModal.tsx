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
import useStyles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import SelectableConfig from './SelectableConfig';

interface IBotToUpdate {
  botResource: string;
  packageResources: string[];
}

interface IPublicProps {
  packageResources: string[];
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
}

const UpdateBotsModal = ({ isLoading, packageResources }: IPrivateProps) => {
  const classes = useStyles();
  const [selectedBots, setSelectedBots] = React.useState<IBotToUpdate[]>([]);
  const [page, setPage] = React.useState(0);
  const [bots, setBots] = React.useState<[IBot[]]>(null);

  React.useEffect(() => {
    loadBotsUsingPackage(0);
  }, []);

  const closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  const updateSelectedBots = () => {
    eddiApiActionDispatchers.updateBotsAction(selectedBots);
    closeModal();
  };

  const loadBotsUsingPackage = async (page: number) => {
    eddiApiActionDispatchers.fetchBotsUsingPackageAction(
      packageResources[page],
      true,
    );
    let bots: [IBot[]];
    if (page === 0) {
      bots = [await getBotsUsingPackage(packageResources[page], true)];
    } else {
      bots = { ...bots };
      bots.push(await getBotsUsingPackage(packageResources[page], true));
    }
    setBots(bots);
  };

  const nextPage = () => {
    setPage(page + 1);
    if (_.isEmpty(bots[page])) {
      loadBotsUsingPackage(page);
    }
  };

  const previousPage = () => {
    setPage(page - 1);
  };

  const selectBot = (botResource: string) => {
    const currentPackageResource = packageResources[page];
    const selectedBot = selectedBots.find(
      (bot) => bot.botResource === botResource,
    );
    if (_.isEmpty(selectedBot)) {
      const newList = selectedBots.map((bot) => bot);
      newList.push({
        botResource: botResource,
        packageResources: [currentPackageResource],
      });
      setSelectedBots(newList);
    } else {
      const newList = selectedBots.filter(
        (bot) => bot.botResource !== botResource,
      );
      if (selectedBot.packageResources.includes(currentPackageResource)) {
        newList.push({
          botResource: selectedBot.botResource,
          packageResources: selectedBot.packageResources.filter(
            (resource) => resource !== currentPackageResource,
          ),
        });
        setSelectedBots(newList);
      } else {
        selectedBot.packageResources.push(currentPackageResource);
        newList.push(selectedBot);
        setSelectedBots(newList);
      }
    }
  };

  const isBotSelected = (botResource: string): boolean => {
    return !!selectedBots.find(
      (selectedBot) =>
        selectedBot.botResource === botResource &&
        selectedBot.packageResources.includes(packageResources[page]),
    );
  };

  const isLastPage = (): boolean => {
    return page === _.size(packageResources) - 1;
  };

  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div
            className={
              classes.title
            }>{`Select bots to update any old versions of the package to latest`}</div>
          <div className={classes.centerFlex} />
          {page > 0 && (
            <WhiteButton
              classes={{ button: classes.backButton }}
              onClick={previousPage}
              text={'Back'}
            />
          )}
          <BlueButton
            classes={{ button: classes.button }}
            onClick={isLastPage() ? updateSelectedBots : nextPage}
            text={isLastPage() ? 'Update selected' : 'Next'}
          />
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.centerFlex} />
          <div className={classes.lastModified}>{'Last modified'}</div>
        </div>
      </div>
      <div>
        {!isLoading && !_.isEmpty(bots) && !_.isEmpty(bots[page]) && (
          <div className={classes.packageList}>
            {bots[page].map((bot, i) => (
              <SelectableConfig
                key={i}
                selected={isBotSelected(bot.resource)}
                descriptor={bot}
                handleClick={selectBot}
              />
            ))}
          </div>
        )}
        {isLoading && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!isLoading && _.isEmpty(bots) && (
          <div>{'Found no bots that can be updated'}</div>
        )}
      </div>
    </div>
  );
};
const ComposedUpdateBotsModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('UpdateBotsModal'),
  connect(loadingBotSelector),
)(UpdateBotsModal);

export default ComposedUpdateBotsModal;
