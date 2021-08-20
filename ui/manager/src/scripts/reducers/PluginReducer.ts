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
  FETCH_PACKAGES_USING_PLUGIN_SUCCESS,
  UPDATE_PLUGIN_SUCCESS,
  CREATE_NEW_PLUGIN_SUCCESS,
  FETCH_PLUGIN_JSON_SCHEMA_SUCCESS,
  DUPLICATE_SUCCESS,
  EDIT_PLUGIN_DATA,
  CLEAR_EDITED_PLUGIN_DATA,
  UPDATE_DESCRIPTOR_SUCCESS,
} from '../actions/EddiApiActionTypes';
import update from 'immutability-helper';
import {
  IFetchPluginFailedAction,
  IFetchPluginSuccessAction,
  IFetchDefaultPluginTypesFailedAction,
  IFetchDefaultPluginTypesSuccessAction,
  IFetchPluginsSuccessAction,
  IFetchPluginsFailedAction,
  IFetchPackagesUsingPluginSuccessAction,
  IUpdatePluginSuccessAction,
  ICreateNewPluginSuccessAction,
  IFetchJsonSchemaSuccessAction,
  IDuplicateSuccessAction,
  IEditPluginDataAction,
  IUpdateDescriptorSuccessAction,
} from '../actions/EddiApiActions';
import {
  IDefaultPluginTypes,
  IEddiSchema,
  IPackage,
  IPlugin,
} from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';
import {
  BEHAVIOR,
  GITCALLS,
  HTTPCALLS,
  OUTPUT,
  PROPERTYSETTER,
  REGULAR_DICTIONARY,
} from '../components/utils/EddiTypes';
import Parser from '../components/utils/Parser';
import PluginHelper from '../components/utils/helpers/PluginHelper';
import { JSONSchema4 } from 'json-schema';

export type IPluginReducer = Reducer<IPluginState>;
export type ITempPluginData = {
  pluginId: string;
  data: string;
  resource: string;
  schema: JSONSchema4;
};

export interface IPluginState {
  error: Error;
  isLoading: boolean;
  isLoadingPlugins: boolean;
  isLoadingAllDefaultPluginTypes: boolean;
  defaultPluginTypes: IDefaultPluginTypes[];
  plugins: IPlugin[];
  schemas: IEddiSchema[];
  tempPluginsData: ITempPluginData[];
  allDictionariesLoaded: boolean;
  allBehaviorsLoaded: boolean;
  allOutputsLoaded: boolean;
  allHttpCallsLoaded: boolean;
  allGitCallsLoaded: boolean;
  allPropertysetterLoaded: boolean;

  loadedDictionaries: number;
  loadedBehaviors: number;
  loadedOutputs: number;
  loadedHttpCalls: number;
  loadedGitCalls: number;
  loadedPropertysetters: number;
}

export const initialState: IPluginState = {
  error: null,
  isLoading: false,
  isLoadingPlugins: false,
  isLoadingAllDefaultPluginTypes: false,
  defaultPluginTypes: [],
  plugins: [],
  schemas: [],
  tempPluginsData: [],
  allDictionariesLoaded: false,
  allBehaviorsLoaded: false,
  allOutputsLoaded: false,
  allHttpCallsLoaded: false,
  allGitCallsLoaded: false,
  allPropertysetterLoaded: false,

  loadedDictionaries: 0,
  loadedBehaviors: 0,
  loadedOutputs: 0,
  loadedHttpCalls: 0,
  loadedGitCalls: 0,
  loadedPropertysetters: 0,
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
      const lastPage =
        (action as IFetchPluginsSuccessAction).limit >
        (action as IFetchPluginsSuccessAction).plugins?.length;
      const newPluginsLoaded = (action as IFetchPluginsSuccessAction).plugins
        ?.length;
      const pluginType = (action as IFetchPluginsSuccessAction).pluginType;
      return update(state, {
        isLoadingPlugins: {
          $set: false,
        },
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            if (!_.isEmpty((action as IFetchPluginsSuccessAction).plugins)) {
              return _.uniqBy(
                plugins.concat((action as IFetchPluginsSuccessAction).plugins),
                (plugin) => plugin.resource,
              );
            } else {
              return plugins;
            }
          },
        },
        loadedDictionaries: {
          $set:
            pluginType === REGULAR_DICTIONARY
              ? state.loadedDictionaries + newPluginsLoaded
              : state.loadedDictionaries,
        },
        allDictionariesLoaded: {
          $set:
            pluginType === REGULAR_DICTIONARY
              ? lastPage
              : state.allDictionariesLoaded,
        },
        loadedBehaviors: {
          $set:
            pluginType === BEHAVIOR
              ? state.loadedBehaviors + newPluginsLoaded
              : state.loadedBehaviors,
        },
        allBehaviorsLoaded: {
          $set: pluginType === BEHAVIOR ? lastPage : state.allBehaviorsLoaded,
        },
        loadedOutputs: {
          $set:
            pluginType === OUTPUT
              ? state.loadedOutputs + newPluginsLoaded
              : state.loadedOutputs,
        },
        allOutputsLoaded: {
          $set: pluginType === OUTPUT ? lastPage : state.allOutputsLoaded,
        },
        loadedHttpCalls: {
          $set:
            pluginType === HTTPCALLS
              ? state.loadedHttpCalls + newPluginsLoaded
              : state.loadedHttpCalls,
        },
        allHttpCallsLoaded: {
          $set: pluginType === HTTPCALLS ? lastPage : state.allHttpCallsLoaded,
        },
        loadedGitCalls: {
          $set:
            pluginType === GITCALLS
              ? state.loadedGitCalls + newPluginsLoaded
              : state.loadedGitCalls,
        },
        allGitCallsLoaded: {
          $set: pluginType === GITCALLS ? lastPage : state.allHttpCallsLoaded,
        },
        loadedPropertysetters: {
          $set:
            pluginType === PROPERTYSETTER
              ? state.loadedPropertysetters + newPluginsLoaded
              : state.loadedPropertysetters,
        },
        allPropertysetterLoaded: {
          $set:
            pluginType === PROPERTYSETTER
              ? lastPage
              : state.allPropertysetterLoaded,
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
        (plugin) =>
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
            return plugins.map((plugin) => {
              if (
                plugin.resource ===
                (action as IFetchPackagesUsingPluginSuccessAction)
                  .pluginResource
              ) {
                return update(plugin, {
                  usedByPackages: {
                    $set: (
                      action as IFetchPackagesUsingPluginSuccessAction
                    ).packages.map((pkg) => pkg.resource),
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
            const newPluginList = plugins.map((plugin) => {
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

    case CREATE_NEW_PLUGIN_SUCCESS:
      const type = Parser.getExtensionType(
        (action as ICreateNewPluginSuccessAction).plugin.resource,
      );
      return update(state, {
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            return plugins.concat(
              (action as ICreateNewPluginSuccessAction).plugin,
            );
          },
        },
        loadedDictionaries: {
          $set:
            type === REGULAR_DICTIONARY
              ? state.loadedDictionaries + 1
              : state.loadedDictionaries,
        },
        loadedBehaviors: {
          $set:
            type === BEHAVIOR
              ? state.loadedBehaviors + 1
              : state.loadedBehaviors,
        },
        loadedOutputs: {
          $set: type === OUTPUT ? state.loadedOutputs + 1 : state.loadedOutputs,
        },
        loadedHttpCalls: {
          $set:
            type === HTTPCALLS
              ? state.loadedHttpCalls + 1
              : state.loadedHttpCalls,
        },
        loadedGitCalls: {
          $set:
            type === GITCALLS ? state.loadedGitCalls + 1 : state.loadedGitCalls,
        },
        loadedPropertysetters: {
          $set:
            type === PROPERTYSETTER
              ? state.loadedPropertysetters + 1
              : state.loadedPropertysetters,
        },
      });

    case FETCH_PLUGIN_JSON_SCHEMA_SUCCESS:
      return update(state, {
        schemas: {
          $apply: (schemas: IEddiSchema[]) => {
            if (!_.isEmpty((action as IFetchJsonSchemaSuccessAction).schema)) {
              return _.uniqBy(
                schemas.concat(
                  (action as IFetchJsonSchemaSuccessAction).schema,
                ),
                (schema) => schema.name,
              );
            } else {
              return schemas;
            }
          },
        },
      });

    case DUPLICATE_SUCCESS: {
      return update(state, {
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            return _.uniqBy(
              plugins.concat((action as IDuplicateSuccessAction).plugins),
              (plugin) => plugin.resource,
            );
          },
        },
        loadedDictionaries: {
          $set:
            state.loadedDictionaries +
            PluginHelper.countResources(
              REGULAR_DICTIONARY,
              (action as IDuplicateSuccessAction).plugins,
            ),
        },
        loadedBehaviors: {
          $set:
            state.loadedBehaviors +
            PluginHelper.countResources(
              BEHAVIOR,
              (action as IDuplicateSuccessAction).plugins,
            ),
        },
        loadedOutputs: {
          $set:
            state.loadedOutputs +
            PluginHelper.countResources(
              OUTPUT,
              (action as IDuplicateSuccessAction).plugins,
            ),
        },
        loadedHttpCalls: {
          $set:
            state.loadedHttpCalls +
            PluginHelper.countResources(
              HTTPCALLS,
              (action as IDuplicateSuccessAction).plugins,
            ),
        },
        loadedGitCalls: {
          $set:
            state.loadedGitCalls +
            PluginHelper.countResources(
              GITCALLS,
              (action as IDuplicateSuccessAction).plugins,
            ),
        },
        loadedPropertysetters: {
          $set:
            state.loadedPropertysetters +
            PluginHelper.countResources(
              PROPERTYSETTER,
              (action as IDuplicateSuccessAction).plugins,
            ),
        },
      });
    }
    case EDIT_PLUGIN_DATA: {
      return update(state, {
        tempPluginsData: {
          $apply: (tempPluginsData: ITempPluginData[]) => {
            const pluginIndex = tempPluginsData.findIndex((d) => {
              return d.pluginId === (action as IEditPluginDataAction).pluginId;
            });
            const newData = {
              pluginId: (action as IEditPluginDataAction).pluginId,
              data: (action as IEditPluginDataAction).data,
              resource: (action as IEditPluginDataAction).resource,
              schema: (action as IEditPluginDataAction).schema,
            };
            if (pluginIndex !== -1) {
              let pluginData = [...tempPluginsData];
              pluginData[pluginIndex] = newData;
              return pluginData;
            } else {
              return [...tempPluginsData, newData];
            }
          },
        },
      });
    }
    case CLEAR_EDITED_PLUGIN_DATA: {
      return update(state, {
        tempPluginsData: { $set: [] },
      });
    }
    case UPDATE_DESCRIPTOR_SUCCESS: {
      console.log('state: ', state);
      console.log('action: ', action);
      return update(state, {
        plugins: {
          $apply: (plugins: IPlugin[]) => {
            return plugins.map((plugin) => {
              if (
                plugin.resource ===
                (action as IUpdateDescriptorSuccessAction).resource
              ) {
                return update(plugin, {
                  description: {
                    $set: (action as IUpdateDescriptorSuccessAction)
                      .description,
                  },
                  name: {
                    $set: (action as IUpdateDescriptorSuccessAction).name,
                  },
                });
              } else {
                return plugin;
              }
            });
          },
        },
      });
    }
    default:
      return state;
  }
};

export default PluginReducer;
