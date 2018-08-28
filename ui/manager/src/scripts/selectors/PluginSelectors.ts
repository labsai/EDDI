import { IAppState } from '../reducers';
import Parser from '../components/utils/Parser';

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
  const plugins = state.pluginState.plugins.filter(
    plug =>
      plug.resource.includes(
        Parser.getPluginName(props.pluginType, true).toLowerCase(),
      ) && plug.version === plug.currentVersion,
  );
  const sortedPlugins = plugins.sort(function(a, b) {
    return a.lastModifiedOn - b.lastModifiedOn;
  });
  return {
    plugins: sortedPlugins || [],
    isLoading: state.pluginState.isLoadingPlugins,
    error: state.pluginState.error,
  };
}
