import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { IPlugin } from '../../utils/AxiosFunctions';
import Parser, { IUsedResource } from '../../utils/Parser';
import Package from './Package';

const styles: { [key: string]: IExtendedCSSProperties } = {
  content: {
    width: '100%',
  },
  seeMore: {
    display: 'inline-block',
    color: '#16325C',
    fontSize: '12px',
    marginTop: '12px',
    minWidth: 'fit-content',
  },
  list: {
    display: 'inline-block',
    minWidth: 'fit-content',
  },
};

interface IProps {
  plugin: IPlugin;
  isSmallName?: boolean;
}

interface IState {
  expandList: boolean;
}

class PackagesUsingPlugin extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      expandList: false,
    };
  }

  componentDidMount() {
    if (!_.isEmpty(this.props.plugin)) {
      eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
        this.props.plugin.resource,
        false,
      );
    }
  }
  componentDidUpdate(prevProps) {
    if (!this.props.plugin.usedByPackages && prevProps !== this.props) {
      eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
        this.props.plugin.resource,
        false,
      );
    }
  }

  expandList = () => {
    this.setState({ expandList: !this.state.expandList });
  };

  render() {
    let shortList: IUsedResource[];
    if (!_.isEmpty(this.props.plugin.usedByPackages)) {
      shortList = Parser.shortenResourceList(this.props.plugin.usedByPackages);
    }
    return (
      <div>
        {!_.isEmpty(this.props.plugin.usedByPackages) && (
          <div style={styles.content}>
            {this.state.expandList ? (
              <div style={styles.list}>
                {this.props.plugin.usedByPackages.map((resource) => (
                  <Package
                    key={resource}
                    packageResource={resource}
                    isSmallName={!!this.props.isSmallName}
                  />
                ))}
              </div>
            ) : (
              <div style={styles.list}>
                {shortList.map((r) => (
                  <Package
                    key={r.resource}
                    packageResource={r.resource}
                    usedByOlderVersion={r.usedByOlderVersion}
                    isSmallName={!!this.props.isSmallName}
                  />
                ))}
              </div>
            )}
            {_.size(this.props.plugin.usedByPackages) > _.size(shortList) &&
              !this.state.expandList && (
                <div style={styles.seeMore} onClick={this.expandList}>
                  {'...See more'}
                </div>
              )}
          </div>
        )}
        {_.size(this.props.plugin.usedByPackages) > _.size(shortList) &&
          this.state.expandList && (
            <div style={styles.seeMore} onClick={this.expandList}>
              {'See less'}
            </div>
          )}
      </div>
    );
  }
}

const ComposedPackagesUsingPlugin: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PackagesUsingPlugin'),
)(PackagesUsingPlugin);

export default ComposedPackagesUsingPlugin;
