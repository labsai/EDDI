import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import ViewJsonContent from './ViewJsonContent';
import { IPlugin } from '../../utils/AxiosFunctions';
import { connect } from 'react-redux';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import * as _ from 'lodash';

interface IPublicProps {
  pluginResource: string;
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
  isLoading: boolean;
  error: Error;
}

const PluginContainer = (props: IPrivateProps) => {
  const [data, setData] = React.useState('');

  React.useEffect(() => {
    setData(JSON.stringify(props.plugin.pluginData, null, '\t'));
  }, [props.plugin, props.isLoading, props.error]);

  return (
    <div>
      {!_.isEmpty(props.plugin) && (
        <ViewJsonContent
          descriptor={props.plugin}
          data={data}
          usedBy={props.plugin.usedByPackages}
          selectVersion={props.selectVersion}
        />
      )}
    </div>
  );
};

const ComposedPluginContainer: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('PluginContainer'),
  connect(pluginSelector),
)(PluginContainer);

export default ComposedPluginContainer;
