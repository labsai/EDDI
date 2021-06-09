import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { packagesSelector } from '../../../selectors/PackageSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';
import { IBot, IPackage } from '../../utils/AxiosFunctions';
import Parser from '../../utils/Parser';
import '../ModalComponent.styles.scss';
import useStyles from './AddPackagesModal.styles';
import PackageContainer from './PackageContainer';

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

const AddPackagesModal = (props: IPrivateProps) => {
  const classes = useStyles();
  const [selectedPackages, setSelectedPackages] = React.useState<string[]>([]);
  const [loading, setLoading] = React.useState<boolean>(false);

  React.useEffect(() => {
    if (props.packagesLoaded < DEFAULT_LIMIT && !props.allPackagesLoaded) {
      eddiApiActionDispatchers.fetchPackagesAction(DEFAULT_LIMIT, 0);
    }
    discardChanges();
  }, []);

  React.useEffect(() => {
    if (!props.isLoading) {
      setLoading(false);
    }
  }, [props.isLoading]);

  const closeModal = () => {
    discardChanges();
    ModalActionDispatchers.closeModal();
  };

  const selectPackage = (packageResource: string) => {
    if (selectedPackages.includes(packageResource)) {
      setSelectedPackages(
        selectedPackages.filter((pack) => pack !== packageResource),
      );
    } else {
      setSelectedPackages(selectedPackages.concat(packageResource));
    }
  };

  const unsavedChanges = (): boolean => {
    return _.isEqual(selectedPackages, props.bot.packages);
  };

  const discardChanges = (): void => {
    setSelectedPackages(props.bot.packages);
  };

  const isPackageSelected = (packageResource: string): boolean => {
    return !!selectedPackages.find(
      (selectedPackage) =>
        Parser.getId(packageResource) === Parser.getId(selectedPackage),
    );
  };

  const getBotPackageIfUsed = (packageResource: string): string => {
    const botPackage = props.bot.packages.find(
      (botPackage) =>
        Parser.getId(packageResource) === Parser.getId(botPackage),
    );
    return botPackage || packageResource;
  };

  const updateBot = () => {
    eddiApiActionDispatchers.updateBotPackagesAction(
      props.bot,
      selectedPackages,
    );
    closeModal();
  };

  const loadMore = () => {
    const fetchIndex = Math.floor(props.packagesLoaded / DEFAULT_LIMIT);
    if (loading || _.isEmpty(props.packages)) {
      return;
    }
    setLoading(true);
    eddiApiActionDispatchers.fetchPackagesAction(DEFAULT_LIMIT, fetchIndex);
  };

  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div
            className={
              classes.title
            }>{`Select packages for <${props.bot.name}>`}</div>
          <div className={classes.centerFlex} />
          <BlueButton
            classes={{ button: classes.button }}
            disabled={unsavedChanges()}
            onClick={updateBot}
            text={'Save changes'}
          />
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.centerFlex} />
          <div className={classes.lastModified}>{'Last modified'}</div>
        </div>
      </div>
      <div className={classes.packageList}>
        <div>
          {props.packages.map((pack, i) => (
            <PackageContainer
              key={i}
              packageResource={getBotPackageIfUsed(pack.resource)}
              selected={isPackageSelected(pack.resource)}
              handleClick={selectPackage}
            />
          ))}
        </div>
        {props.isLoading && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!props.allPackagesLoaded && !props.isLoading && !loading && (
          <BlueButton
            classes={{ button: classes.loadMoreButton }}
            onClick={loadMore}
            text={'Load More'}
          />
        )}
      </div>
    </div>
  );
};
const ComposedAddPackagesModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('AddPackagesModal'),
  connect(packagesSelector),
)(AddPackagesModal);

export default ComposedAddPackagesModal;
