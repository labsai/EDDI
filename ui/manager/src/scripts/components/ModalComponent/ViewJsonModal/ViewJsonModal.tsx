import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import Parser from '../../utils/Parser';
import { PACKAGE } from '../../utils/EddiTypes';
import PackageContainer from './PackageContainer';
import PluginContainer from './PluginContainer';

interface IPrivateProps extends IPublicProps {}

interface IPublicProps {
  resource: string;
}

interface IState {
  selectedResource: string;
}

class ViewJsonModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedResource: this.props.resource,
    };
  }

  async componentDidMount() {
    if (this.isPackage()) {
      eddiApiActionDispatchers.fetchPackageAction(this.props.resource);
    } else {
      eddiApiActionDispatchers.fetchPluginAction(this.props.resource);
    }
    this.setState({
      selectedResource: this.props.resource,
    });
  }

  componentWillReceiveProps(nextProps) {
    this.setState({
      selectedResource: nextProps.resource,
    });
  }

  selectVersion = (newVersion: number) => {
    const selectedResource = Parser.replaceResourceVersion(
      this.state.selectedResource,
      newVersion,
    );
    this.setState({
      selectedResource,
    });
    if (this.isPackage()) {
      eddiApiActionDispatchers.fetchPackageAction(selectedResource);
    } else {
      eddiApiActionDispatchers.fetchPluginAction(selectedResource);
    }
  };

  isPackage() {
    return this.props.resource.includes(PACKAGE);
  }

  render() {
    const isPackage = this.isPackage();
    return (
      <div>
        {renderIf(isPackage)(() => (
          <PackageContainer
            packageResource={this.state.selectedResource}
            selectVersion={this.selectVersion}
          />
        ))}
        {renderIf(!isPackage)(() => (
          <PluginContainer
            pluginResource={this.state.selectedResource}
            selectVersion={this.selectVersion}
          />
        ))}
      </div>
    );
  }
}

const ComposedViewJsonModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('ViewJsonModal'),
)(ViewJsonModal);

export default ComposedViewJsonModal;
