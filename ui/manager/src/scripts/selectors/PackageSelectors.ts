import { IAppState } from '../reducers';
import { IBot, IPackage } from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';
import Parser from '../components/utils/Parser';

export interface IPackageSelectorProps {
  packageResource: string;
  packagePayload: IPackage;
  pack: IPackage;
}

export function packagesSelector(state: IAppState) {
  const packages = state.packageState.packages.filter(
    (pkg) => pkg.version === pkg.currentVersion,
  );
  const sortedPackages = packages.sort(function (a, b) {
    return b.lastModifiedOn - a.lastModifiedOn;
  });
  return {
    packages: state.packageState.allPackagesLoaded
      ? sortedPackages
      : sortedPackages.slice(0, state.packageState.packagesLoaded),
    allPackagesLoaded: state.packageState.allPackagesLoaded,
    packagesLoaded: state.packageState.packagesLoaded,
    isLoading: state.packageState.isLoadingAllPackages,
    error: state.packageState.error,
  };
}

export interface IPackagesWithPluginSelectorProps {
  pluginResource: string;
}
export function packagesWithPluginSelector(
  state: IAppState,
  props: IPackagesWithPluginSelectorProps,
) {
  const packages = state.packageState.packages.filter(
    (pkg) =>
      pkg.version === pkg.currentVersion &&
      !_.isEmpty(pkg.packageData) &&
      JSON.stringify(pkg.packageData).includes(
        Parser.getId(props.pluginResource),
      ),
  );
  const sortedPackages = packages.sort(function (a, b) {
    return b.lastModifiedOn - a.lastModifiedOn;
  });
  return {
    packages: sortedPackages,
    isLoading: state.packageState.isLoadingAllPackages,
    error: state.packageState.error,
  };
}

export function packageSelector(
  state: IAppState,
  props: IPackageSelectorProps,
) {
  const pkg = state.packageState.packages.find(
    (pkg) => pkg.resource === props.packageResource,
  );
  return {
    packagePayload: pkg,
    isLoading:
      state.packageState.isLoadingPackage &&
      state.packageState.isLoadingAllPackages,
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
    (pack) => pack.id === props.packageId,
  );
  const packagePayload = _.maxBy(packages, (p) => p.version);
  return {
    packagePayload,
    error: state.packageState.error,
    isLoading: state.packageState.isLoadingPackage,
  };
}

export interface ISpecificPackageSelectorProps {
  packageId: string;
  version: string;
}

export function specificPackageSelector(
  state: IAppState,
  props: ISpecificPackageSelectorProps,
) {
  let packagePayload: IPackage;
  if (!_.isEmpty(props.version)) {
    packagePayload = state.packageState.packages.find(
      (pack) =>
        pack.id === props.packageId &&
        pack.version.toString() === props.version,
    );
  } else {
    packagePayload = state.packageState.packages.find(
      (pack) =>
        pack.id === props.packageId && pack.version === pack.currentVersion,
    );
  }
  return {
    packagePayload,
    error: state.packageState.error,
    isLoading: state.packageState.isLoadingPackage,
  };
}
