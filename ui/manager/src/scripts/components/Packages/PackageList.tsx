import * as React from 'react';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import Radium from 'radium';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { IPackage } from '../utils/AxiosFunctions';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { getAPIUrl } from '../utils/ApiFunctions';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import PackageContainer from '../BotDetailView/PackageContainer';
import styles from './Packagelist.styles';
import { packagesSelector } from '../../selectors/PackageSelectors';
const InfiniteScroll = require('react-infinite-scroller') as InfiniteScrollTypes;

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

interface IState {
  apiUrl: string;
  loading: boolean;
}

class PackageList extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      apiUrl: '',
      loading: false,
    };
  }

  async componentDidMount() {
    this.loadMore();
    this.setState({ apiUrl: await getAPIUrl() });
  }

  componentDidUpdate(prevProps) {
    if (prevProps.isLoading && !this.props.isLoading) {
      this.setState({ loading: false });
    }
  }

  filterPackages() {
    if (!_.isEmpty(this.props.filterText)) {
      return this.props.packages.filter(
        pkg =>
          pkg.name
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()) ||
          pkg.id.toLowerCase().includes(this.props.filterText.toLowerCase()),
      );
    } else {
      return this.props.packages;
    }
  }

  loadMore = () => {
    if (this.state.loading) {
      return;
    }
    this.setState({ loading: true });
    const loadNumber = 5;
    if (
      this.props.packages.length < loadNumber &&
      !this.props.allPackagesLoaded
    ) {
      eddiApiActionDispatchers.fetchPackagesAction(loadNumber, 0);
    } else {
      eddiApiActionDispatchers.fetchPackagesAction(
        loadNumber,
        Math.floor(this.props.packagesLoaded / loadNumber),
      );
    }
  };

  render() {
    const packageList = this.filterPackages();
    return (
      <div>
        {renderIf(this.props.isLoading && _.isEmpty(this.props.packages))(
          () => (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          ),
        )}
        {renderIf(this.props.error)(() => (
          <p>{'Error: Could not load bots'}</p>
        ))}
        {renderIf(
          !this.props.isLoading &&
            !this.props.error &&
            _.isEmpty(this.props.packages),
        )(() => <p>{`There are no packages yet`}</p>)}
        {renderIf(!this.props.error && !_.isEmpty(this.props.packages))(() => (
          <div style={styles.packageList}>
            {renderIf(_.isEmpty(packageList))(() => (
              <p>{`Found no packages matching: "${this.props.filterText}"`}</p>
            ))}
            <InfiniteScroll
              pageStart={0}
              loadMore={this.loadMore}
              hasMore={!this.props.allPackagesLoaded && !this.props.isLoading}
              loader={
                <div className="loader" key={0}>
                  Loading ...
                </div>
              }>
              {packageList.map(pkg => (
                <PackageContainer
                  key={pkg.id}
                  packageResource={pkg.resource}
                />
              ))}
            </InfiniteScroll>
          </div>
        ))}
      </div>
    );
  }
}

const ComposedPackageList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
    pure,
    connect(packagesSelector),
    Radium,
    setDisplayName('PackageList'),
  )(
  PackageList,
);

export default ComposedPackageList;
