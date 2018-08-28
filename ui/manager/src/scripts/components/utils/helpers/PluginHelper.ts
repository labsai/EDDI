import * as _ from 'lodash';
import Parser from '../Parser';
import { IPlugin } from '../AxiosFunctions';
import { IOptions } from '../../PackageDetailView/PackageView';
import { getDate } from '../DateFormat';
import { ReactDOM } from 'react';

export default class PluginHelper {
  static getName(
    pluginType: IOptions,
    plugin: IPlugin,
    isCapitalized: boolean,
  ): string {
    const defaultPluginName = Parser.getPluginName(
      pluginType.type,
      isCapitalized,
    );
    if (!_.isEmpty(plugin) && defaultPluginName !== 'Parser') {
      return plugin.name || defaultPluginName;
    } else {
      return defaultPluginName;
    }
  }
  static getLastModified(
    pluginType: IOptions,
    plugin: IPlugin,
    isCapitalized: boolean,
    html: any,
  ): any {
    const defaultPluginName = Parser.getPluginName(
      pluginType.type,
      isCapitalized,
    );
    if (!_.isEmpty(plugin) && defaultPluginName !== 'Parser') {
      return getDate(plugin.lastModifiedOn);
    } else {
      return html;
    }
  }

  static getVersion(
    pluginType: IOptions,
    plugin: IPlugin,
    isCapitalized: boolean,
  ): string {
    if (
      !_.isEmpty(plugin) &&
      Parser.getPluginName(pluginType.type, isCapitalized) !== 'Parser'
    ) {
      return `v${plugin.version}`;
    } else {
      return '';
    }
  }
}
