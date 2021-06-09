import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { IBot } from '../utils/AxiosFunctions';
import { ModalEnum } from '../utils/ModalEnum';
import PackageContainer from './PackageContainer';
import { makeStyles } from '@material-ui/core/styles';

const useStyles = makeStyles({
  packagesHeader: {
    display: 'flex',
    marginTop: '50px',
  },
  headerCenter: {
    flex: 1,
  },
  packagesTitle: {
    color: '#54698D',
    fontSize: '12px',
    textAlign: 'left',
    marginTop: '10px',
  },
  button: {
    marginLeft: '10px',
  },
  packages: {
    marginBottom: '100px',
  },
});

interface IPublicProps {
  bot: IBot;
  readOnly: boolean;
}

interface IPrivateProps extends IPublicProps {}

const PackageList = ({ bot, readOnly }: IPrivateProps) => {
  const classes = useStyles();

  React.useEffect(() => {
    if (_.isUndefined(bot.packages)) {
      eddiApiActionDispatchers.fetchBotDataAction(bot);
    }
  }, []);

  const openModal = () => {
    ModalActionDispatchers.showModal(ModalEnum.createPackage);
  };

  const openAddPackagesModal = () => {
    ModalActionDispatchers.showAddPackagesModal(bot);
  };

  const isCurrentVersion = bot.version !== bot.currentVersion;
  return (
    <div className={classes.packages}>
      <div className={classes.packagesHeader}>
        <div className={classes.packagesTitle}>{'PACKAGES'}</div>
        <div className={classes.headerCenter} />
        <WhiteButton
          text={'Create package'}
          onClick={openModal}
          disabled={isCurrentVersion || readOnly}
        />
        <WhiteButton
          text={'Add package'}
          onClick={openAddPackagesModal}
          disabled={isCurrentVersion || readOnly}
          classes={{ button: classes.button }}
        />
      </div>
      {_.isEmpty(bot.packages) ? (
        <p>{`There are no packages yet`}</p>
      ) : (
        <div>
          {bot.packages.map((pack) => (
            <PackageContainer key={pack} packageResource={pack} />
          ))}
        </div>
      )}
    </div>
  );
};

const ComposedPackageList: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('PackageList'),
)(PackageList);

export default ComposedPackageList;
