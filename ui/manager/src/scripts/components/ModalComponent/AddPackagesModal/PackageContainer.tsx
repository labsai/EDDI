import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import Parser from '../../utils/Parser';
import Package from './Package';

interface IProps {
  packageResource: string;
  selected: boolean;
  handleClick(resource: string): void;
}

const PackageContainer = (props: IProps) => {
  const [selectedPackageResource, setSelectedPackageResource] = React.useState(
    props.packageResource,
  );

  React.useEffect(() => {
    setSelectedPackageResource(props.packageResource);
  }, [props.packageResource]);

  const selectVersion = (resource: string, newVersion: number) => {
    const selectedPackageResource = Parser.replaceResourceVersion(
      resource,
      newVersion,
    );
    setSelectedPackageResource(selectedPackageResource);
    eddiApiActionDispatchers.fetchPackageAction(selectedPackageResource);
  };

  const handleClick = () => {
    props.handleClick(selectedPackageResource);
  };

  return (
    <Package
      selected={props.selected}
      handleClick={handleClick}
      packageResource={selectedPackageResource}
      selectVersion={selectVersion}
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
