import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import Parser from '../utils/Parser';
import Plugin from './Plugin';

interface IPublicProps {
  pluginResource: string;
}

interface IPrivateProps extends IPublicProps {}

const PluginContainer = (props: IPrivateProps) => {
  const [selectedResource, setSelectedResource] = React.useState<string>(
    props.pluginResource,
  );

  const selectVersion = (newVersion: number) => {
    const newResource = Parser.replaceResourceVersion(
      props.pluginResource,
      newVersion,
    );
    eddiApiActionDispatchers.fetchPluginAction(newResource);
    setSelectedResource(newResource);
  };

  return (
    <div>
      <Plugin pluginResource={selectedResource} selectVersion={selectVersion} />
    </div>
  );
};

const ComposedPluginContainer: React.ComponentClass<IPrivateProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('PluginContainer'),
)(PluginContainer);

export default ComposedPluginContainer;
