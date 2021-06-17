import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { PACKAGE } from '../../utils/EddiTypes';
import Parser from '../../utils/Parser';
import '../ModalComponent.styles.scss';
import PackageContainer from './PackageContainer';
import PluginContainer from './PluginContainer';

interface IPublicProps {
  resource: string;
}

interface IPrivateProps extends IPublicProps {}

interface IState {
  selectedResource: string;
}

const ViewJsonModal = ({ resource }: IPrivateProps) => {
  const [selectedResource, setSelectedResource] =
    React.useState<string>(resource);

  React.useEffect(() => {
    if (isPackage()) {
      eddiApiActionDispatchers.fetchPackageAction(resource);
    } else {
      eddiApiActionDispatchers.fetchPluginAction(resource);
    }
    setSelectedResource(resource);
  }, []);

  React.useEffect(() => {
    setSelectedResource(resource);
  }, [resource]);

  const selectVersion = (newVersion: number) => {
    const tempSelectedResource = Parser.replaceResourceVersion(
      selectedResource,
      newVersion,
    );
    setSelectedResource(tempSelectedResource);
    if (isPackage()) {
      eddiApiActionDispatchers.fetchPackageAction(tempSelectedResource);
    } else {
      eddiApiActionDispatchers.fetchPluginAction(tempSelectedResource);
    }
  };

  const isPackage = () => {
    return resource.includes(PACKAGE);
  };

  return (
    <div>
      {isPackage() && (
        <PackageContainer
          packageResource={selectedResource}
          selectVersion={selectVersion}
        />
      )}
      {!isPackage() && (
        <PluginContainer
          pluginResource={selectedResource}
          selectVersion={selectVersion}
        />
      )}
    </div>
  );
};

const ComposedViewJsonModal: React.ComponentClass<IPrivateProps> = compose<
  IPrivateProps,
  IPrivateProps
>(
  pure,
  setDisplayName('ViewJsonModal'),
)(ViewJsonModal);

export default ComposedViewJsonModal;
