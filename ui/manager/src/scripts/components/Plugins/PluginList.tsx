import * as React from 'react';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { connect } from 'react-redux';
import { ClimbingBoxLoader } from 'react-spinners';
import { getAPIUrl } from '../utils/ApiFunctions';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import PluginContainer from './PluginContainer';
import styles from './Pluginlist.styles';
import { pluginsSelector } from '../../selectors/PluginSelectors';
import { IPlugin } from '../utils/AxiosFunctions';
import Parser from '../utils/Parser';
const InfiniteScroll = require('react-infinite-scroller') as InfiniteScrollTypes;

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

  componentWillReceiveProps(nextProps) {
    if (nextProps.pluginType !== this.props.pluginType) {
      this.loadMore(nextProps);
    }
    if (this.props.isLoading && !nextProps.isLoading) {
      this.setState({ loading: false });
    }
  }

  filterPlugins() {
    if (!_.isEmpty(this.props.filterText)) {
      return this.props.plugins.filter(
        plugin =>
          plugin.name
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()) ||
          plugin.id.toLowerCase().includes(this.props.filterText.toLowerCase()),
      );
    } else {
      return this.props.plugins;
    }
  }

  loadMore = (props = this.props) => {
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
    }
  };

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
        <div>
          {renderIf(this.props.error)(() => (
            <p>{`Error: Could not load ${pluginName.toLowerCase()}`}</p>
          ))}
          {renderIf(!this.props.error && _.isEmpty(this.props.plugins))(() => (
            <p>{`There are no ${pluginName.toLowerCase()} yet`}</p>
          ))}
          {renderIf(!this.props.error && !_.isEmpty(this.props.plugins))(() => (
            <div>
              {renderIf(_.isEmpty(pluginList))(() => (
                <p>{`Found no ${pluginName.toLowerCase()} matching: "${
                  this.props.filterText
                }"`}</p>
              ))}
              <InfiniteScroll
                pageStart={0}
                loadMore={this.loadMore}
                hasMore={
                  !this.props.isAllPluginsLoaded && !this.props.isLoading
                }
                loader={
                  <div className="loader" key={0}>
                    Loading ...
                  </div>
                }>
                {pluginList.map(plugin => (
                  <PluginContainer
                    key={plugin.id}
                    pluginResource={plugin.resource}
                  />
                ))}
              </InfiniteScroll>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedPluginList: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(pure, Radium, connect(pluginsSelector), setDisplayName('PluginList'))(
  PluginList,
);

export default ComposedPluginList;
