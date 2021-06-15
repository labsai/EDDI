import { IAppState } from '../reducers';
import { createSelector } from 'reselect';
import Parser from '../components/utils/Parser';
import {
  BEHAVIOR,
  GITCALLS,
  HTTPCALLS,
  OUTPUT,
  PROPERTYSETTER,
  REGULAR_DICTIONARY,
} from '../components/utils/EddiTypes';
import { IPluginState } from '../reducers/PluginReducer';

export function defaultPluginTypesSelector(state: IAppState) {
  return {
    defaultPluginTypes: state.pluginState.defaultPluginTypes || [],
  };
}
export interface IPluginSelectorProps {
  pluginResource: string;
}

export function pluginSelector(state: IAppState, props: IPluginSelectorProps) {
  const plugin = state.pluginState.plugins.find(
    (plug) => plug.resource === props.pluginResource,
  );
  return {
    plugin: plugin || [],
    isLoading: state.pluginState.isLoading,
    error: state.pluginState.error,
  };
}

export const PluginStateSelector: (state: IAppState) => IPluginState = (
  state,
) => state.pluginState;

const getPluginType = (_, pluginType: string) => pluginType;

export interface IPluginsSelectorProps {
  pluginType: string;
}
export const pluginsSelector = createSelector(
  PluginStateSelector,
  getPluginType,
  (pluginState: IPluginState, pluginType: string) => {
    let isAllPluginsLoaded;
    let loadedPlugins;
    let pluginName = Parser.getPluginName(pluginType, false);
    switch (pluginType) {
      case REGULAR_DICTIONARY:
        isAllPluginsLoaded = pluginState.allDictionariesLoaded;
        loadedPlugins = pluginState.loadedDictionaries;
        break;
      case BEHAVIOR:
        isAllPluginsLoaded = pluginState.allBehaviorsLoaded;
        loadedPlugins = pluginState.loadedBehaviors;
        break;
      case OUTPUT:
        isAllPluginsLoaded = pluginState.allOutputsLoaded;
        loadedPlugins = pluginState.loadedOutputs;
        break;
      case HTTPCALLS:
        isAllPluginsLoaded = pluginState.allHttpCallsLoaded;
        loadedPlugins = pluginState.loadedHttpCalls;
        break;
      case GITCALLS:
        isAllPluginsLoaded = pluginState.allGitCallsLoaded;
        loadedPlugins = pluginState.loadedGitCalls;
        break;
      case PROPERTYSETTER:
        isAllPluginsLoaded = pluginState.allPropertysetterLoaded;
        loadedPlugins = pluginState.loadedPropertysetters;
        break;
      default:
        isAllPluginsLoaded = false;
        loadedPlugins = 0;
        break;
    }
    const plugins = pluginState.plugins.filter(
      (plug) =>
        plug.resource.includes(pluginName) &&
        plug.version === plug.currentVersion,
    );
    const sortedPlugins = plugins.sort(function (a, b) {
      return b.lastModifiedOn - a.lastModifiedOn;
    });
    return {
      plugins:
        (sortedPlugins
          ? sortedPlugins
          : sortedPlugins.slice(0, loadedPlugins)) || [],
      isAllPluginsLoaded,
      loadedPlugins,
      isLoading: pluginState.isLoadingPlugins,
      error: pluginState.error,
    };
  },
);
