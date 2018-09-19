import { IAppState } from '../reducers';
import { IBot, IPackage } from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';

export interface IPackageSelectorProps {
  packageResource: string;
  packagePayload: IPackage;
  pack: IPackage;
}

export function packagesSelector(state: IAppState) {
  const packages = state.packageState.packages.filter(
    pkg => pkg.version === pkg.currentVersion,
  );
  const sortedPackages = packages.sort(function(a, b) {
    return b.lastModifiedOn - a.lastModifiedOn;
  });
  return {
    packages: sortedPackages,
    isLoading: state.packageState.isLoadingAllPackages,
    error: state.packageState.error,
  };
}

export interface IPackagesWithPluginSelectorProps {
  pluginid: string;
}
export function packagesWithPluginSelector(
  state: IAppState,
  props: IPackagesWithPluginSelectorProps,
) {
  const packages = state.packageState.packages.filter(
    pkg => pkg.version === pkg.currentVersion,
  );
}

export function packageSelector(
  state: IAppState,
  props: IPackageSelectorProps,
) {
  const pkg = state.packageState.packages.find(
    pkg => pkg.resource === props.packageResource,
  );
  return {
    packagePayload: pkg,
    isLoading: state.packageState.isLoadingPackage,
    error: state.packageState.error,
  };
}

export interface ILatestPackageSelectorProps {
  packageId: string;
}

export function latestPackageSelector(
  state: IAppState,
  props: ILatestPackageSelectorProps,
) {
  const packages = state.packageState.packages.filter(
    pack => pack.id === props.packageId,
  );
  const packagePayload = _.maxBy(packages, p => p.version);
  return {
    packagePayload,
    error: state.packageState.error,
    isLoading: state.packageState.isLoadingPackage,
  };
}

export function pluginTypesSelector(
  state: IAppState,
  props: IPackageSelectorProps,
) {
  const pack = state.packageState.packages.find(
    pack => pack.resource === props.packagePayload.resource,
  );
  return {
    oldTypes: pack.pluginTypes || [],
  };
}
