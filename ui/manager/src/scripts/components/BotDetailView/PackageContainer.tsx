import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import Parser from '../utils/Parser';
import Package from './Package';

interface IProps {
  packageResource: string;
  botId?: string;
}

const PackageContainer = (props: IProps) => {
  const [selectedPackageResource, setSelectedPackageResource] =
    React.useState('');

  React.useEffect(() => {
    eddiApiActionDispatchers.fetchPackageAction(props.packageResource);
    setSelectedPackageResource(props.packageResource);
  }, [props.packageResource]);

  const selectVersion = (newVersion: number) => {
    const selectedPackageResource = Parser.replaceResourceVersion(
      props.packageResource,
      newVersion,
    );
    setSelectedPackageResource(selectedPackageResource);
    eddiApiActionDispatchers.fetchPackageAction(selectedPackageResource);
  };

  return (
    <Package
      isPackageInBot={selectedPackageResource !== props.packageResource}
      packageResource={selectedPackageResource}
      selectVersion={selectVersion}
      botId={props.botId}
    />
  );
};

const ComposedPackageContainer: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PackageContainer'),
)(PackageContainer);

export default ComposedPackageContainer;
