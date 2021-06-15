import * as _ from 'lodash';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { useSelector } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { pluginsSelector } from '../../selectors/PluginSelectors';
import { IPlugin } from '../utils/AxiosFunctions';
import Parser from '../utils/Parser';
import PluginContainer from './PluginContainer';
import useStyles from './PluginList.styles';
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

const PluginList = ({ error, filterText, pluginType }: IPrivateProps) => {
  console.log('pluginType: ', pluginType);
  const [loading, setLoading] = React.useState(false);
  const { isLoading, plugins, isAllPluginsLoaded, loadedPlugins } = useSelector(
    (state) => pluginsSelector(state, pluginType),
  );

  const classes = useStyles();

  React.useEffect(() => {
    if (!isLoading) {
      loadMore();
    }
  }, [pluginType]);

  React.useEffect(() => {
    if (!isLoading) {
      setLoading(false);
    }
  }, [isLoading]);

  const filterPlugins = () => {
    if (!_.isEmpty(filterText)) {
      return plugins.filter(
        (plugin) =>
          plugin.name.toLowerCase().includes(filterText.toLowerCase()) ||
          plugin.id.toLowerCase().includes(filterText.toLowerCase()),
      );
    } else {
      return plugins;
    }
  };

  const loadMore = () => {
    if (loading || !pluginType) {
      return;
    }
    setLoading(true);
    if (plugins.length < 5 && !isAllPluginsLoaded) {
      eddiApiActionDispatchers.fetchPluginsAction(pluginType, 5, 0);
    } else {
      eddiApiActionDispatchers.fetchPluginsAction(
        pluginType,
        5,
        Math.floor(loadedPlugins / 5),
      );
      return;
    }
  };

  const pluginList: IPlugin[] = filterPlugins();
  const pluginName = Parser.getFullPluginName(pluginType, true, true);
  return (
    <div>
      <div className={classes.topHeader}>
        <div className={classes.title}>{pluginName}</div>
        <div className={classes.lastModified}>{'Last Modified'}</div>
      </div>
      {error && <p>{`Error: Could not load ${pluginName.toLowerCase()}`}</p>}
      {!isLoading && !error && _.isEmpty(plugins) && (
        <p>{`There are no ${pluginName.toLowerCase()} yet`}</p>
      )}
      {!error && !_.isEmpty(plugins) && (
        <div className={classes.pluginList}>
          {_.isEmpty(pluginList) && (
            <p>{`Found no ${pluginName.toLowerCase()} matching: "${filterText}"`}</p>
          )}
          <InfiniteScroll
            pageStart={0}
            loadMore={loadMore}
            hasMore={!isAllPluginsLoaded}
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
};

const ComposedPluginList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  setDisplayName('PluginList'),
)(PluginList);

export default ComposedPluginList;
