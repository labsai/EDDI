import { Reducer, Action } from 'redux';
import { IPackage } from '../components/utils/AxiosFunctions';
import {
  FETCH_PACKAGEDATA,
  FETCH_PACKAGEDATA_FAILED,
  FETCH_PACKAGEDATA_SUCCESS,
  FETCH_PACKAGE,
  FETCH_PACKAGE_FAILED,
  FETCH_PACKAGE_SUCCESS,
  ADD_AVAILABLE_UPDATE_FOR_PACKAGE,
  UPDATE_PACKAGE_SUCCESS,
  FETCH_PACKAGES,
  FETCH_PACKAGES_SUCCESS,
  FETCH_PACKAGES_FAILED,
  FETCH_CURRENT_PACKAGE,
  UPDATE_DESCRIPTOR_SUCCESS,
  FETCH_PLUGIN_TYPES_IN_PACKAGE_SUCCESS,
  UPDATE_PLUGIN_TYPE_IN_PACKAGE_SUCCESS,
  FETCH_BOTS_USING_PACKAGE_SUCCESS,
  FETCH_PACKAGES_USING_PLUGIN_SUCCESS,
  UPDATE_PACKAGES,
} from '../actions/EddiApiActionTypes';
import * as update from 'immutability-helper';
import {
  IFetchPackageDataFailedAction,
  IFetchPackageDataSuccessAction,
  IFetchPackageFailedAction,
  IFetchPackageSuccessAction,
  IAddAvailableUpdateForPackageAction,
  IUpdatePackageSuccessAction,
  IFetchPackagesSuccessAction,
  IFetchPackagesFailedAction,
  IUpdateDescriptorSuccessAction,
  IUpdatePluginTypeSuccessAction,
  IFetchPluginTypesSuccessAction,
  IFetchBotsUsingPackageSuccessAction,
  IFetchPackagesUsingPluginSuccessAction,
  IUpdatePackagesSuccessAction,
} from '../actions/EddiApiActions';
import * as _ from 'lodash';
import { parsePluginExtensions } from '../components/utils/helpers/PluginParser';

export type IPackageReducer = Reducer<IPackageState>;

export interface IPackageState {
  error: Error;
  isLoadingAllPackages: boolean;
  isLoadingPackageData: boolean;
  isLoadingPackage: boolean;
  packages: IPackage[];
}

export const initialState: IPackageState = {
  error: null,
  isLoadingAllPackages: false,
  isLoadingPackageData: false,
  isLoadingPackage: false,
  packages: [],
};

const PackageReducer: IPackageReducer = (
  state: IPackageState = initialState,
  action?: Action,
): IPackageState => {
  if (!action) {
    return state;
  }

  switch (action.type) {
    case FETCH_PACKAGES:
      return update(state, {
        isLoadingAllPackages: {
          $set: true,
        },
      });

    case FETCH_PACKAGES_SUCCESS:
      const newPackageList = (action as IFetchPackagesSuccessAction).packages.map(
        newPackage => {
          const oldPackage = state.packages.find(
            pkg => pkg.resource === newPackage.resource,
          );
          if (_.isEmpty(oldPackage)) {
            return newPackage;
          } else {
            return oldPackage;
          }
        },
      );
      return update(state, {
        packages: {
          $set: newPackageList,
        },
        isLoadingAllPackages: {
          $set: false,
        },
      });

    case FETCH_PACKAGES_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchPackagesFailedAction).error,
        },
        isLoadingAllPackages: {
          $set: false,
        },
      });

    case FETCH_PACKAGE:
      return update(state, {
        isLoadingPackage: {
          $set: true,
        },
      });

    case FETCH_PACKAGE_SUCCESS:
      const packageList = state.packages.filter(
        pkg =>
          pkg.resource !==
          (action as IFetchPackageSuccessAction).package.resource,
      );
      packageList.push((action as IFetchPackageSuccessAction).package);
      return update(state, {
        isLoadingPackage: {
          $set: false,
        },
        packages: {
          $apply: (packages: IPackage[]) => {
            return packageList;
          },
        },
      });

    case FETCH_PACKAGE_FAILED: {
      return update(state, {
        error: {
          $set: (action as IFetchPackageFailedAction).error,
        },
        isLoadingPackage: {
          $set: false,
        },
      });
    }

    case FETCH_CURRENT_PACKAGE:
      return update(state, {
        isLoadingPackage: {
          $set: true,
        },
      });

    case FETCH_PACKAGEDATA:
      return update(state, {
        isLoadingPackageData: {
          $set: true,
        },
      });

    case FETCH_PACKAGEDATA_SUCCESS:
      return update(state, {
        isLoadingPackageData: {
          $set: false,
        },
        packages: {
          $apply: (packages: IPackage[]) => {
            return packages.map(pack => {
              if (
                pack.resource ===
                (action as IFetchPackageDataSuccessAction).packageResource
              ) {
                return update(pack, {
                  packageData: {
                    $set: (action as IFetchPackageDataSuccessAction)
                      .packageData,
                  },
                });
              }
              return pack;
            });
          },
        },
      });

    case FETCH_PACKAGEDATA_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchPackageDataFailedAction).error,
        },
        isLoadingPackageData: {
          $set: false,
        },
      });

    case UPDATE_PACKAGE_SUCCESS:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            const updatedPackage = (action as IUpdatePackageSuccessAction)
              .package;
            const newPackageList = packages.map(pack => {
              if (pack.id === updatedPackage.id) {
                return update(pack, {
                  currentVersion: { $set: updatedPackage.version },
                  updatablePlugins: { $set: [] },
                });
              } else {
                return pack;
              }
            });
            newPackageList.push(updatedPackage);
            return newPackageList;
          },
        },
      });

    case ADD_AVAILABLE_UPDATE_FOR_PACKAGE:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            return packages.map(pack => {
              if (
                pack.resource ===
                (action as IAddAvailableUpdateForPackageAction).packageResource
              ) {
                if (pack.updatablePlugins) {
                  if (
                    !pack.updatablePlugins.includes(
                      (action as IAddAvailableUpdateForPackageAction)
                        .pluginResource,
                    )
                  ) {
                    return update(pack, {
                      updatablePlugins: {
                        $push: [
                          (action as IAddAvailableUpdateForPackageAction)
                            .pluginResource,
                        ],
                      },
                    });
                  }
                } else {
                  return update(pack, {
                    updatablePlugins: {
                      $set: [
                        (action as IAddAvailableUpdateForPackageAction)
                          .pluginResource,
                      ],
                    },
                  });
                }
              }
              return pack;
            });
          },
        },
      });

    case UPDATE_DESCRIPTOR_SUCCESS:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            return packages.map(pack => {
              if (
                pack.resource ===
                (action as IUpdateDescriptorSuccessAction).resource
              ) {
                return update(pack, {
                  description: {
                    $set: (action as IUpdateDescriptorSuccessAction)
                      .description,
                  },
                  name: {
                    $set: (action as IUpdateDescriptorSuccessAction).name,
                  },
                });
              } else {
                return pack;
              }
            });
          },
        },
      });

    case FETCH_PLUGIN_TYPES_IN_PACKAGE_SUCCESS:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            return packages.map(pack => {
              if (
                pack.resource ===
                (action as IFetchPluginTypesSuccessAction).packageResource
              ) {
                return update(pack, {
                  pluginTypes: {
                    $set: (action as IFetchPluginTypesSuccessAction)
                      .pluginTypes,
                  },
                });
              } else {
                return pack;
              }
            });
          },
        },
      });

    case UPDATE_PLUGIN_TYPE_IN_PACKAGE_SUCCESS:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            const updatedPackage = (action as IUpdatePluginTypeSuccessAction)
              .packagePayload;
            const newPackageList = packages.map(pack => {
              if (pack.id === updatedPackage.id) {
                return update(pack, {
                  currentVersion: { $set: updatedPackage.version },
                  updatablePlugins: { $set: [] },
                });
              } else {
                return pack;
              }
            });
            newPackageList.push(updatedPackage);
            return newPackageList;
          },
        },
      });

    case FETCH_BOTS_USING_PACKAGE_SUCCESS:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            return packages.map(pack => {
              if (
                pack.resource ===
                (action as IFetchBotsUsingPackageSuccessAction).packageResource
              ) {
                return update(pack, {
                  usedByBots: {
                    $set: (action as IFetchBotsUsingPackageSuccessAction).bots.map(
                      bot => bot.resource,
                    ),
                  },
                });
              } else {
                return pack;
              }
            });
          },
        },
      });

    case FETCH_PACKAGES_USING_PLUGIN_SUCCESS:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            if (
              !_.isEmpty(
                (action as IFetchPackagesUsingPluginSuccessAction).packages,
              )
            ) {
              return _.uniqBy(
                packages.concat(
                  (action as IFetchPackagesUsingPluginSuccessAction).packages,
                ),
                pkg => pkg.resource,
              );
            } else {
              return packages;
            }
          },
        },
      });

    case UPDATE_PACKAGES:
      return update(state, {
        packages: {
          $apply: (packages: IPackage[]) => {
            const updatedPackages: IPackage[] = (action as IUpdatePackagesSuccessAction)
              .packages;
            const newPackageList = packages.map(pack => {
              for (let i = 0; i < _.size(updatedPackages); i++) {
                if (pack.id === updatedPackages[i].id) {
                  return update(pack, {
                    currentVersion: { $set: updatedPackages[i].version },
                    updatablePlugins: { $set: [] },
                  });
                }
              }
              return pack;
            });
            return newPackageList.concat(updatedPackages);
          },
        },
      });

    default:
      return state;
  }
};

export default PackageReducer;
