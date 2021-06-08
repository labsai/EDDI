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
import Options from '../Assets/Buttons/Options';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import { IPackage } from '../utils/AxiosFunctions';
import useStyles from './Package.styles';
import PluginList from './PluginList';

interface IPublicProps {
  isPackageInBot: boolean;
  packageResource: string;
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
              onClick={() =>
                historyPush(
                  `/packageview/${packagePayload.id}`,
                  isCurrentVersion
                    ? null
                    : [`version=${packagePayload.version}`],
                )
              }>
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
              <div
                className={classes.options}
                onClick={(e) => e.stopPropagation()}>
                <Options
                  descriptor={packagePayload}
                  data={JSON.stringify(packagePayload.packageData, null, '\t')}
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
              <PluginList packagePayload={packagePayload} />
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
