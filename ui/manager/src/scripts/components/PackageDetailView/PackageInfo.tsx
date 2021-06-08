import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { specificPackageSelector } from '../../selectors/PackageSelectors';
import useStyles from '../Bots/Botlist.styles';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import { IPackage } from '../utils/AxiosFunctions';
import { PACKAGE, PACKAGE_PATH } from '../utils/EddiTypes';
import PackageView from './PackageView';

interface IPublicProps {
  packageId: string;
  version: string;
}

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

const PackageInfo = ({
  packageId,
  version,
  packagePayload,
  isLoading,
  error,
}: IPrivateProps) => {
  const classes = useStyles();

  React.useEffect(() => {
    if (_.isEmpty(version)) {
      eddiApiActionDispatchers.fetchCurrentPackageAction(packageId);
    } else {
      eddiApiActionDispatchers.fetchPackageAction(
        `${PACKAGE}${PACKAGE_PATH}/${packageId}?version=${version}`,
      );
    }
  }, []);

  return (
    <div>
      <HomeButtonComponent />
      {isLoading && (
        <div className={classes.loadingWrapper}>
          <ClimbingBoxLoader loading />
        </div>
      )}
      {!isLoading && (
        <div>
          {!!error && <p>{'Error: Could not load package'}</p>}
          {!error && _.isEmpty(packagePayload) && <p>{'Package not found'}</p>}
          {!error && !_.isEmpty(packagePayload) && (
            <PackageView packagePayload={packagePayload} />
          )}
        </div>
      )}
    </div>
  );
};

const ComposedPackageInfo: React.ComponentClass<IPublicProps, IPrivateProps> =
  compose<IPublicProps, IPrivateProps>(
    pure,
    connect(specificPackageSelector),
    setDisplayName('PackageInfo'),
  )(PackageInfo);

export default ComposedPackageInfo;
