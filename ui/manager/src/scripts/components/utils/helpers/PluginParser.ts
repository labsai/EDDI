import {
  IPluginExtensions,
  IPluginTypes,
  IPluginExtension,
} from '../AxiosFunctions';
import * as _ from 'lodash';

export const parsePlugins: (
  externalPackages: IPluginExtensions[],
) => string[] = (externalPackages: IPluginExtensions[]) => {
  const plugins: string[] = [];
  externalPackages.map(externalPackage => {
    if (externalPackage.config.uri) {
      plugins.push(externalPackage.config.uri);
    }
    for (let extensions of Object.values(externalPackage.extensions)) {
      for (let extension of extensions) {
        if (extension.config && extension.config.uri) {
          plugins.push(extension.config.uri);
        }
      }
    }
  });
  return plugins;
};

export const parsePlugin: (externalPackages: IPluginTypes) => string = (
  externalPackage: IPluginTypes,
) => {
  let plugin: string = '';
  if (externalPackage.config && externalPackage.config.uri) {
    plugin = externalPackage.config.uri;
  }
  if (!_.isEmpty(externalPackage.extensions)) {
    for (let extensionTypes of Object.values(externalPackage.extensions)) {
      for (let extension of extensionTypes.extensions) {
        if (extension.resource) {
          plugin = extension.resource;
        }
      }
    }
  }
  return plugin;
};

export const parsePluginTypes: (pluginTypes: IPluginTypes[]) => string[] = (
  pluginTypes: IPluginTypes[],
) => {
  return pluginTypes
    .filter(pluginType => pluginType.type)
    .map(pluginType => pluginType.type);
};

export const parsePluginExtensions: (
  externalPackages: IPluginExtensions[],
) => IPluginExtension[] = (externalPackages: IPluginExtensions[]) => {
  const plugins: IPluginExtension[] = [];
  for (let externalPackage of externalPackages) {
    let packageExtension: IPluginExtension = {
      type: externalPackage.type,
      extensions: [],
    };
    if (externalPackage.config.uri) {
      packageExtension.resource = externalPackage.config.uri;
    }
    for (let extensions of Object.values(externalPackage.extensions)) {
      for (let extension of extensions) {
        if (extension.config && extension.config.uri) {
          const newExtension: IPluginExtension = {
            type: extension.type,
            resource: extension.config.uri,
          };
          packageExtension.extensions.push(newExtension);
        }
      }
    }
    plugins.push(packageExtension);
  }
  return plugins;
};

export const hasExtensions = (plugin: IPluginExtensions) => {
  if (_.isEmpty(plugin.extensions)) {
    return false;
  }
  for (let extensions of Object.values(plugin.extensions)) {
    if (extensions.length > 0) {
      return true;
    }
  }
  return false;
};
