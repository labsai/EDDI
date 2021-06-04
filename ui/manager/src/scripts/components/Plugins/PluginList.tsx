import * as _ from 'lodash';
import Radium from 'radium';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { pluginsSelector } from '../../selectors/PluginSelectors';
import { getAPIUrl } from '../utils/ApiFunctions';
import { IPlugin } from '../utils/AxiosFunctions';
import Parser from '../utils/Parser';
import PluginContainer from './PluginContainer';
import styles from './PluginList.styles';
const InfiniteScroll =
  require('react-infinite-scroller') as InfiniteScrollTypes;

interface IPublicProps {
  filterText: string;
  pluginType: string;
}

interface IPrivateProps extends IPublicProps {
  plugins: IPlugin[];
  isLoading: boolean;
  isAllPluginsLoaded: boolean;
  error: Error;
  loadedPlugins: number;
}

interface IState {
  apiUrl: string;
  loading: boolean;
}

class PluginList extends React.Component<IPrivateProps, IState> {
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
    if (prevProps.pluginType !== this.props.pluginType) {
      this.loadMore();
    }
    if (prevProps.isLoading && !this.props.isLoading) {
      this.setState({ loading: false });
    }
  }

  filterPlugins() {
    if (!_.isEmpty(this.props.filterText)) {
      return this.props.plugins.filter(
        (plugin) =>
          plugin.name
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()) ||
          plugin.id.toLowerCase().includes(this.props.filterText.toLowerCase()),
      );
    } else {
      return this.props.plugins;
    }
  }

  loadMore(props = this.props) {
    if (this.state.loading || !props.pluginType) {
      return;
    }
    this.setState({ loading: true });
    if (props.plugins.length < 5 && !props.isAllPluginsLoaded) {
      eddiApiActionDispatchers.fetchPluginsAction(props.pluginType, 5, 0);
    } else {
      eddiApiActionDispatchers.fetchPluginsAction(
        props.pluginType,
        5,
        Math.floor(props.loadedPlugins / 5),
      );
      return;
    }
  }

  render() {
    const pluginList: IPlugin[] = this.filterPlugins();
    const pluginName = Parser.getFullPluginName(
      this.props.pluginType,
      true,
      true,
    );
    return (
      <div>
        <div style={styles.topHeader}>
          <div style={styles.title}>{pluginName}</div>
          <div style={styles.lastModified}>{'Last Modified'}</div>
        </div>
        {this.props.error && (
          <p>{`Error: Could not load ${pluginName.toLowerCase()}`}</p>
        )}
        {!this.props.isLoading &&
          !this.props.error &&
          _.isEmpty(this.props.plugins) && (
            <p>{`There are no ${pluginName.toLowerCase()} yet`}</p>
          )}
        {!this.props.error && !_.isEmpty(this.props.plugins) && (
          <div style={styles.pluginList}>
            {_.isEmpty(pluginList) && (
              <p>{`Found no ${pluginName.toLowerCase()} matching: "${
                this.props.filterText
              }"`}</p>
            )}
            <InfiniteScroll
              pageStart={0}
              loadMore={() => this.loadMore()}
              hasMore={!this.props.isAllPluginsLoaded}
              loader={
                <div className="loader" key={0}>
                  Loading ...
                </div>
              }>
              {pluginList.map((plugin) => (
                <PluginContainer
                  key={plugin.id}
                  pluginResource={plugin.resource}
                />
              ))}
            </InfiniteScroll>
          </div>
        )}
      </div>
    );
  }
}

const ComposedPluginList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  connect(pluginsSelector),
  Radium,
  setDisplayName('PluginList'),
)(PluginList);

export default ComposedPluginList;
