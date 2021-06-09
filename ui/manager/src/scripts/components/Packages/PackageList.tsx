import * as _ from 'lodash';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { packagesSelector } from '../../selectors/PackageSelectors';
import PackageContainer from '../BotDetailView/PackageContainer';
import { IPackage } from '../utils/AxiosFunctions';
import useStyles from './Packagelist.styles';
const InfiniteScroll =
  require('react-infinite-scroller') as InfiniteScrollTypes;

interface IPublicProps {
  filterText: string;
}

interface IPrivateProps extends IPublicProps {
  packages: IPackage[];
  isLoading: boolean;
  allPackagesLoaded: boolean;
  error: Error;
  packagesLoaded: number;
}

const PackageList = ({
  packages,
  isLoading,
  allPackagesLoaded,
  error,
  packagesLoaded,
  filterText,
}: IPrivateProps) => {
  const [loading, setLoading] = React.useState(false);

  const classes = useStyles();

  React.useEffect(() => {
    if (!isLoading) {
      loadMore();
    }
  }, []);

  React.useEffect(() => {
    if (!isLoading) {
      setLoading(false);
    }
  }, [isLoading]);

  const filterPackages = () => {
    if (!_.isEmpty(filterText)) {
      return packages.filter(
        (pkg) =>
          pkg.name.toLowerCase().includes(filterText.toLowerCase()) ||
          pkg.id.toLowerCase().includes(filterText.toLowerCase()),
      );
    } else {
      return packages;
    }
  };

  const loadMore = () => {
    if (loading) {
      return;
    }
    setLoading(true);
    const loadNumber = 5;
    if (packages.length < loadNumber && !allPackagesLoaded) {
      eddiApiActionDispatchers.fetchPackagesAction(loadNumber, 0);
    } else {
      eddiApiActionDispatchers.fetchPackagesAction(
        loadNumber,
        Math.floor(packagesLoaded / loadNumber),
      );
    }
  };

  const packageList = filterPackages();
  return (
    <div>
      {isLoading && _.isEmpty(packages) && (
        <div className={classes.loadingWrapper}>
          <ClimbingBoxLoader loading />
        </div>
      )}
      {!!error && !isLoading && <p>{'Error: Could not load packages'}</p>}
      {!isLoading && !error && _.isEmpty(packages) && (
        <p>{`There are no packages yet`}</p>
      )}
      {!error && !_.isEmpty(packages) && (
        <div className={classes.packageList}>
          {_.isEmpty(packageList) && (
            <p>{`Found no packages matching: "${filterText}"`}</p>
          )}
          <InfiniteScroll
            pageStart={0}
            loadMore={loadMore}
            hasMore={!allPackagesLoaded && !isLoading}
            loader={
              <div className="loader" key={0}>
                Loading ...
              </div>
            }>
            {packageList.map((pkg) => (
              <PackageContainer key={pkg.id} packageResource={pkg.resource} />
            ))}
          </InfiniteScroll>
        </div>
      )}
    </div>
  );
};

const ComposedPackageList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  connect(packagesSelector),
  setDisplayName('PackageList'),
)(PackageList);

export default ComposedPackageList;
