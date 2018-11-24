import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
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

  componentWillReceiveProps(nextProps) {
    this.setState({
      selectedPackageResource: nextProps.packageResource,
    });
  }

  selectVersion = (resource: string, newVersion: number) => {
    if (this.props.selected) {
      this.props.handleClick(this.state.selectedPackageResource);
    }
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

const ComposedPackageContainer: Component<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('PackageContainer'),
)(PackageContainer);

export default ComposedPackageContainer;
