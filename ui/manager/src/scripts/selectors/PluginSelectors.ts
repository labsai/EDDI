import { IAppState } from '../reducers';
import Parser from '../components/utils/Parser';
import {
  BEHAVIOR,
  HTTPCALLS,
  OUTPUT,
  PROPERTYSETTER,
  REGULAR_DICTIONARY,
} from '../components/utils/EddiTypes';

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
    plug => plug.resource === props.pluginResource,
  );
  return {
    plugin: plugin || [],
    isLoading: state.pluginState.isLoading,
    error: state.pluginState.error,
  };
}

export interface IPluginsSelectorProps {
  pluginType: string;
}
export function pluginsSelector(
  state: IAppState,
  props: IPluginsSelectorProps,
) {
  let isAllPluginsLoaded;
  let loadedPlugins;
  switch (props.pluginType) {
    case REGULAR_DICTIONARY:
      isAllPluginsLoaded = state.pluginState.allDictionariesLoaded;
      loadedPlugins = state.pluginState.loadedDictionaries;
      break;
    case BEHAVIOR:
      isAllPluginsLoaded = state.pluginState.allBehaviorsLoaded;
      loadedPlugins = state.pluginState.loadedBehaviors;
      break;
    case OUTPUT:
      isAllPluginsLoaded = state.pluginState.allOutputsLoaded;
      loadedPlugins = state.pluginState.loadedOutputs;
      break;
    case HTTPCALLS:
      isAllPluginsLoaded = state.pluginState.allHttpcallsLoaded;
      loadedPlugins = state.pluginState.loadedHttpcalls;
      break;
    case PROPERTYSETTER:
      isAllPluginsLoaded = state.pluginState.allHttpcallsLoaded;
      loadedPlugins = state.pluginState.loadedHttpcalls;
      break;
    default:
      isAllPluginsLoaded = false;
      loadedPlugins = 0;
      break;
  }
  const plugins = state.pluginState.plugins.filter(
    plug =>
      plug.resource.includes(
        Parser.getPluginName(props.pluginType, true).toLowerCase(),
      ) && plug.version === plug.currentVersion,
  );
  const sortedPlugins = plugins.sort(function(a, b) {
    return b.lastModifiedOn - a.lastModifiedOn;
  });
  return {
    plugins: sortedPlugins
      ? sortedPlugins
      : sortedPlugins.slice(0, loadedPlugins),
    isAllPluginsLoaded,
    loadedPlugins,
    isLoading: state.pluginState.isLoadingPlugins,
    error: state.pluginState.error,
  };
}
