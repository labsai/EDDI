import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import ViewJsonContent from './ViewJsonContent';
import { IPlugin } from '../../utils/AxiosFunctions';
import { connect } from 'react-redux';
import * as renderIf from 'render-if';
import { pluginSelector } from '../../../selectors/PluginSelectors';

interface IPublicProps {
  pluginResource: string;
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
  isLoading: boolean;
  error: Error;
}

interface IState {
  data: string;
}

class PluginContainer extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      data: '',
    };
  }

  componentDidMount() {
    this.setState({
      data: JSON.stringify(this.props.plugin.pluginData, null, '\t'),
    });
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.setState({
        data: JSON.stringify(this.props.plugin.pluginData, null, '\t'),
      });
    }
  }

  render() {
    return (
      <div>
        {renderIf(this.props.plugin)(() => (
          <ViewJsonContent
            descriptor={this.props.plugin}
            data={this.state.data}
            usedBy={this.props.plugin.usedByPackages}
            selectVersion={this.props.selectVersion}
          />
        ))}
      </div>
    );
  }
}

const ComposedPluginContainer: Component<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(pure, setDisplayName('PluginContainer'), connect(pluginSelector))(
  PluginContainer,
);

export default ComposedPluginContainer;
