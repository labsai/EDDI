import { Reducer, Action } from 'redux';
import {
  FETCH_PLUGIN,
  FETCH_PLUGIN_FAILED,
  FETCH_PLUGIN_SUCCESS,
  FETCH_DEFAULT_PLUGIN_TYPES,
  FETCH_DEFAULT_PLUGIN_TYPES_FAILED,
  FETCH_DEFAULT_PLUGIN_TYPES_SUCCESS,
  FETCH_PLUGINS,
  FETCH_PLUGINS_SUCCESS,
  FETCH_PLUGINS_FAILED,
  FETCH_BOTS_USING_PACKAGE_SUCCESS,
  FETCH_PACKAGES_USING_PLUGIN_SUCCESS,
  UPDATE_PACKAGE_SUCCESS,
  UPDATE_PLUGIN_SUCCESS,
} from '../actions/EddiApiActionTypes';
import * as update from 'immutability-helper';
import {
  IFetchPluginFailedAction,
  IFetchPluginSuccessAction,
  IFetchDefaultPluginTypesFailedAction,
  IFetchDefaultPluginTypesSuccessAction,
  IFetchPackageSuccessAction,
  IFetchPluginsSuccessAction,
  IFetchPackageFailedAction,
  IFetchPluginsFailedAction,
  IFetchBotsUsingPackageSuccessAction,
  IFetchPackagesUsingPluginAction,
  IFetchPackagesUsingPluginSuccessAction,
  IUpdatePackageSuccessAction,
  IUpdatePluginSuccessAction,
} from '../actions/EddiApiActions';
import {
  IDefaultPluginTypes,
  IPackage,
  IPlugin,
} from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';
import ModalActionDispatchers, {
  default as modalActionDispatchers,
} from '../actions/ModalActionDispatchers';

export type IPluginReducer = Reducer<IPluginState>;

export interface IPluginState {
  error: Error;
  isLoading: boolean;
  isLoadingPlugins: boolean;
  isLoadingAllDefaultPluginTypes: boolean;
  isLoadingAllPluginData: boolean;
  defaultPluginTypes: IDefaultPluginTypes[];
  plugins: IPlugin[];
}

export const initialState: IPluginState = {
  error: null,
  isLoading: false,
  isLoadingPlugins: false,
  isLoadingAllDefaultPluginTypes: false,
  isLoadingAllPluginData: false,
  defaultPluginTypes: [],
  plugins: [],
};

const PluginReducer: IPluginReducer = (
  state: IPluginState = initialState,
  action?: Action,
): IPluginState => {
  if (!action) {
    return state;
  }
  switch (action.type) {
    case FETCH_DEFAULT_PLUGIN_TYPES:
      return update(state, {
        isLoadingAllDefaultPluginTypes: {
          $set: true,
        },
      });

    case FETCH_DEFAULT_PLUGIN_TYPES_SUCCESS:
      return update(state, {
        isLoadingAllDefaultPluginTypes: {
          $set: false,
        },
        defaultPluginTypes: {
          $set: (action as IFetchDefaultPluginTypesSuccessAction)
            .defaultPluginTypes,
        },
      });

    case FETCH_DEFAULT_PLUGIN_TYPES_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchDefaultPluginTypesFailedAction).error,
        },
        isLoadingAllDefaultPluginTypes: {
          $set: false,
        },
      });

    case FETCH_PLUGINS:
      return update(state, {
        isLoadingPlugins: {
          $set: true,
        },
      });

    case FETCH_PLUGINS_SUCCESS:
      return update(state, {
        isLoadingPlugins: {
          $set: false,
        },
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            if (!_.isEmpty((action as IFetchPluginsSuccessAction).plugins)) {
              return _.uniqBy(
                plugins.concat((action as IFetchPluginsSuccessAction).plugins),
                plugin => plugin.resource,
              );
            } else {
              return plugins;
            }
          },
        },
      });

    case FETCH_PLUGINS_FAILED:
      return update(state, {
        isLoadingPlugins: {
          $set: false,
        },
        error: {
          $set: (action as IFetchPluginsFailedAction).error,
        },
      });

    case FETCH_PLUGIN:
      return update(state, {
        isLoading: {
          $set: true,
        },
      });

    case FETCH_PLUGIN_SUCCESS:
      const pluginList = state.plugins.filter(
        plugin =>
          plugin.resource !==
          (action as IFetchPluginSuccessAction).plugin.resource,
      );
      pluginList.push((action as IFetchPluginSuccessAction).plugin);
      return update(state, {
        isLoading: {
          $set: false,
        },
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            return pluginList;
          },
        },
      });

    case FETCH_PLUGIN_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchPluginFailedAction).error,
        },
        isLoading: {
          $set: false,
        },
      });

    case FETCH_PACKAGES_USING_PLUGIN_SUCCESS:
      return update(state, {
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            return plugins.map(plugin => {
              if (
                plugin.resource ===
                (action as IFetchPackagesUsingPluginSuccessAction)
                  .pluginResource
              ) {
                return update(plugin, {
                  usedByPackages: {
                    $set: (action as IFetchPackagesUsingPluginSuccessAction).packages.map(
                      pkg => pkg.resource,
                    ),
                  },
                });
              } else {
                return plugin;
              }
            });
          },
        },
      });

    case UPDATE_PLUGIN_SUCCESS:
      return update(state, {
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            const updatedPlugin = (action as IUpdatePluginSuccessAction).plugin;
            const newPluginList = plugins.map(plugin => {
              if (plugin.id === updatedPlugin.id) {
                return update(plugin, {
                  currentVersion: { $set: updatedPlugin.version },
                });
              } else {
                return plugin;
              }
            });
            newPluginList.push(updatedPlugin);
            return newPluginList;
          },
        },
      });

    default:
      return state;
  }
};

export default PluginReducer;
