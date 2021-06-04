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

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.setState({
        selectedResource: this.props.resource,
      });
    }
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
        {isPackage && (
          <PackageContainer
            packageResource={this.state.selectedResource}
            selectVersion={this.selectVersion}
          />
        )}
        {!isPackage && (
          <PluginContainer
            pluginResource={this.state.selectedResource}
            selectVersion={this.selectVersion}
          />
        )}
      </div>
    );
  }
}

const ComposedViewJsonModal: React.ComponentClass<IPrivateProps> = compose<
  IPrivateProps,
  IPrivateProps
>(
  pure,
  setDisplayName('ViewJsonModal'),
)(ViewJsonModal);

export default ComposedViewJsonModal;
