import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Parser from '../utils/Parser';
import Plugin from './Plugin';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';

interface IPublicProps {
  pluginResource: string;
}

interface IPrivateProps extends IPublicProps {}

interface IState {
  selectedResource: string;
}

class PluginContainer extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedResource: this.props.pluginResource,
    };
  }

  selectVersion = (newVersion: number) => {
    const newResource = Parser.replaceResourceVersion(
      this.props.pluginResource,
      newVersion,
    );
    eddiApiActionDispatchers.fetchPluginAction(newResource);
    this.setState({
      selectedResource: newResource,
    });
  };

  render() {
    return (
      <div>
        <Plugin
          pluginResource={this.state.selectedResource}
          selectVersion={this.selectVersion}
        />
      </div>
    );
  }
}

const ComposedPluginContainer: Component<IPrivateProps> = compose<
  IPrivateProps,
  IPublicProps
>(pure, setDisplayName('PluginContainer'))(PluginContainer);

export default ComposedPluginContainer;
