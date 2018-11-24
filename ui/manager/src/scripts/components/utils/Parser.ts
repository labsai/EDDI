import * as urlParser from 'route-parser';
import * as PluginType from './EddiTypes';
import { IDescriptorResponse, IDetailedDescriptor } from './AxiosFunctions';
import * as _ from 'lodash';

const pattern = new urlParser(
  '(eddi://):resourceType/:path1/:path2/:id?version=:version',
);

const pluginTypePattern = new urlParser(
  '(eddi://):uriType.:uriType2.:uriName(.:extensionName)(.:extensionType)',
);
const extensionPattern = new urlParser(
  '(eddi://):uriType.:uriType2.:uriName.:extensionName.:extensionType',
);

export interface IUsedResource {
  resource: string;
  usedByOlderVersion: boolean;
}

export interface IQueryStringProperties {
  version?: string;
}

export default class Parser {
  static getId(uri: string): string {
    return pattern.match(uri).id;
  }

  static getVersion(uri: string): number {
    return parseInt(pattern.match(uri).version, 10);
  }

  static getApiPathWithIdAndVersion(uri: string): string {
    return this.getApiPath(uri) + this.getIdAndVersion(uri);
  }

  static getIdAndVersion(uri: string): string {
    return this.getId(uri) + '?version=' + this.getVersion(uri);
  }

  static replaceResourceVersion(uri: string, version: number): string {
    const myURL = new URL(uri);
    myURL.searchParams.set('version', version.toString());
    return myURL.toString();
  }

  static getApiPath(uri: string): string {
    const apiPath = `/${pattern.match(uri).path1}/${pattern.match(uri).path2}/`;
    return apiPath;
  }

  static getPluginName(pluginType: string, isCapitalized: boolean): string {
    if (pluginType === PluginType.REGULAR_DICTIONARY) {
      return 'Dictionary';
    } else {
      const pluginTypeName = pluginTypePattern.match(pluginType).uriName;
      if (isCapitalized) {
        return pluginTypeName.charAt(0).toUpperCase() + pluginTypeName.slice(1);
      } else {
        return pluginTypeName;
      }
    }
  }

  static getExtensionType(extensionType: string): string {
    const type = `${extensionPattern.match(extensionType).extensionName}.${
      extensionPattern.match(extensionType).extensionType
    }`;
    return type;
  }

  static getVersionString(version: number, capitalized = true): string {
    const versionString = `v${version > 9 ? version : '0' + version}`;
    return capitalized ? versionString.toUpperCase() : versionString;
  }

  static getDetailedDescriptors(
    data: IDescriptorResponse,
    currentVersion = false,
  ): IDetailedDescriptor[] {
    if (currentVersion) {
      return data.data.map(pkg => {
        const version = Parser.getVersion(pkg.resource);
        return {
          id: Parser.getId(pkg.resource),
          version,
          currentVersion: version,
          createdOn: pkg.createdOn,
          description: pkg.description,
          lastModifiedOn: pkg.lastModifiedOn,
          name: pkg.name,
          resource: pkg.resource,
        };
      });
    }
    return data.data.map(pkg => {
      return {
        id: Parser.getId(pkg.resource),
        version: Parser.getVersion(pkg.resource),
        createdOn: pkg.createdOn,
        description: pkg.description,
        lastModifiedOn: pkg.lastModifiedOn,
        name: pkg.name,
        resource: pkg.resource,
      };
    });
  }

  static shortenResourceList(list: string[]): IUsedResource[] {
    let longList = list.map(resource => resource);
    const shorterList: IUsedResource[] = [];
    while (_.size(longList) > 0) {
      const currentResource = longList[0];
      const currentId = Parser.getId(currentResource);
      const resourcesWithSameId = longList.filter(
        res => Parser.getId(res) === currentId,
      );
      const hasOlderVersion = _.size(resourcesWithSameId) > 1;
      shorterList.push({
        resource: _.maxBy(resourcesWithSameId, function(o) {
          return Parser.getVersion(o);
        }),
        usedByOlderVersion: hasOlderVersion,
      });
      longList = longList.filter(
        resource => Parser.getId(resource) !== currentId,
      );
    }
    return shorterList;
  }

  static getQueryStrings(search = ''): IQueryStringProperties {
    const hashes = search.slice(search.indexOf(`?`) + 1).split(`&`);
    const queryStringObject = {};
    for (let i = 0; i < hashes.length; i++) {
      const query = hashes[i].split('=');
      queryStringObject[query[0]] = query[1];
    }
    return queryStringObject;
  }
}
