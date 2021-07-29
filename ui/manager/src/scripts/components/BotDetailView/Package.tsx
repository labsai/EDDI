import clsx from 'clsx';
import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { historyPush } from '../../history';
import { packageSelector } from '../../selectors/PackageSelectors';
import BlueButton from '../Assets/Buttons/BlueButton';
import Options from '../Assets/Buttons/Options';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import { IPackage, IPlugins } from '../utils/AxiosFunctions';
import useStyles from './Package.styles';
import PluginList from './PluginList';

interface IPublicProps {
  isPackageInBot: boolean;
  packageResource: string;
  botId?: string;
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  packagePayload: IPackage;
  isLoading: boolean;
}

const warningIcon = require('../../../public/images/WarningIcon.png');

const Package = ({
  isPackageInBot,
  packageResource,
  selectVersion,
  error,
  packagePayload,
  isLoading,
  botId,
}: IPrivateProps) => {
  const classes = useStyles();
  const fetchPlugins = () => {
    if (
      !_.isUndefined(packagePayload) &&
      _.isUndefined(packagePayload.packageData)
    ) {
      eddiApiActionDispatchers.fetchPackageDataAction(packageResource);
    }
  };

  const [isChangingOrdering, setIsChangingOrdering] = React.useState(false);
  const [packageExtensionsOrder, setPackageExtensionsOrder] =
    React.useState(null);

  const extensions = packagePayload?.packageData?.packageExtensions;

  React.useEffect(() => {
    if (extensions) {
      setPackageExtensionsOrder(extensions);
    }
  }, [extensions]);

  React.useEffect(() => {
    fetchPlugins();
  }, [
    isPackageInBot,
    packageResource,
    selectVersion,
    error,
    packagePayload,
    isLoading,
  ]);

  const handleSelectVersion = (newVersion: number) => {
    selectVersion(newVersion);
  };

  const handleChangeOrdering = () => {
    setIsChangingOrdering(true);
  };

  const handleCloseChangeOrdering = (
    e: React.MouseEvent<Element, MouseEvent>,
  ) => {
    e.stopPropagation();
    setIsChangingOrdering(false);
    setPackageExtensionsOrder(extensions);
  };

  const handleSaveOrdering = (e: React.MouseEvent<Element, MouseEvent>) => {
    e.stopPropagation();
    eddiApiActionDispatchers.updateExtensionsOrderAction(
      packagePayload,
      packageExtensionsOrder,
    );
    setIsChangingOrdering(false);
  };

  const isCurrentVersion: boolean =
    !_.isEmpty(packagePayload) &&
    packagePayload.version === packagePayload.currentVersion;
  return (
    <div>
      {isLoading && _.isEmpty(packagePayload) && <p>{'Loading package'}</p>}
      <div>
        {!!error && <p>{'Error: Could not load package'}</p>}
        {!error && !_.isEmpty(packagePayload) && (
          <div className={classes.pack}>
            <div
              className={classes.packageHeader}
              onClick={() => {
                const query = [];
                if (!isCurrentVersion) {
                  query.push(`version=${packagePayload.version}`);
                }
                if (botId) {
                  query.push(`botId=${botId}`);
                }
                historyPush(
                  `/packageview/${packagePayload.id}`,

                  query,
                );
              }}>
              <div
                className={clsx(classes.packageName, {
                  [classes.red]: isPackageInBot,
                })}>
                {packagePayload.name || packagePayload.id}
              </div>
              <div
                onClick={(e) => e.stopPropagation()}
                className={classes.version}>
                <VersionSelectComponent
                  selectedVersion={packagePayload.version}
                  currentVersion={packagePayload.currentVersion}
                  selectVersion={handleSelectVersion}
                />
              </div>
              {!_.isEmpty(packagePayload.updatablePlugins) && (
                <div className={classes.warning}>
                  <img src={warningIcon} className={classes.warningIcon} />
                  <div className={classes.updateAvailable}>
                    {'Updates Available'}
                  </div>
                </div>
              )}
              <div className={classes.centerFlex} />
              {isChangingOrdering && (
                <>
                  <BlueButton
                    classes={{ button: classes.saveOrdering }}
                    onClick={handleSaveOrdering}
                    text={'Save Order'}
                  />
                  <BlueButton
                    classes={{ button: classes.closeChangeOrdering }}
                    onClick={handleCloseChangeOrdering}
                    text={'Cancel'}
                  />
                </>
              )}
              <div
                className={classes.options}
                onClick={(e) => e.stopPropagation()}>
                <Options
                  descriptor={packagePayload}
                  data={JSON.stringify(packagePayload.packageData, null, '\t')}
                  changeOrdering={
                    !isChangingOrdering ? handleChangeOrdering : undefined
                  }
                />
              </div>
              <button
                disabled={!isCurrentVersion}
                className={clsx(classes.editPackage, {
                  [classes.editPackageDisabled]:
                    packagePayload.version !== packagePayload.currentVersion,
                })}
                onClick={() =>
                  historyPush(`/packageview/${packagePayload.id}`)
                }>
                {'View package'}
              </button>
            </div>
            {_.isUndefined(packagePayload.packageData) && (
              <ClipLoader color={BLUE_COLOR} />
            )}
            <div className={classes.packageContent}>
              <PluginList
                plugins={packageExtensionsOrder}
                setPlugins={setPackageExtensionsOrder}
                packagePayload={packagePayload}
                packageId={packagePayload.id}
                botId={botId}
                isChangingOrdering={isChangingOrdering}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

const ComposedPackage: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(packageSelector),
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
