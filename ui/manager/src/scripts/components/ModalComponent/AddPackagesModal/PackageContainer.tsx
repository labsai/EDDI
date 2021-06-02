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

interface IState {
  selectedPackageResource: string;
}

class PackageContainer extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPackageResource: this.props.packageResource,
    };
  }

  async componentDidMount() {
    this.setState({
      selectedPackageResource: this.props.packageResource,
    });
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.setState({
        selectedPackageResource: this.props.packageResource,
      });
    }
  }

  selectVersion = (resource: string, newVersion: number) => {
    const selectedPackageResource = Parser.replaceResourceVersion(
      resource,
      newVersion,
    );
    this.setState({
      selectedPackageResource,
    });
    eddiApiActionDispatchers.fetchPackageAction(selectedPackageResource);
  };

  handleClick = () => {
    this.props.handleClick(this.state.selectedPackageResource);
  };

  render() {
    return (
      <Package
        selected={this.props.selected}
        handleClick={this.handleClick}
        packageResource={this.state.selectedPackageResource}
        selectVersion={this.selectVersion}
      />
    );
  }
}

const ComposedPackageContainer: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('PackageContainer'),
)(PackageContainer);

export default ComposedPackageContainer;
