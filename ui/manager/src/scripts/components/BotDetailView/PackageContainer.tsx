import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import Parser from '../utils/Parser';
import Package from './Package';

interface IProps {
  packageResource: string;
}

interface IState {
  selectedPackageResource: string;
}

class PackageContainer extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPackageResource: '',
    };
  }

  async componentDidMount() {
    eddiApiActionDispatchers.fetchPackageAction(this.props.packageResource);
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

  selectVersion = (newVersion: number) => {
    const selectedPackageResource = Parser.replaceResourceVersion(
      this.props.packageResource,
      newVersion,
    );
    this.setState({
      selectedPackageResource,
    });
    eddiApiActionDispatchers.fetchPackageAction(selectedPackageResource);
  };

  render() {
    return (
      <Package
        isPackageInBot={
          this.state.selectedPackageResource !== this.props.packageResource
        }
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
