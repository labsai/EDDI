import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import ViewJsonContent from './ViewJsonContent';
import { IPackage } from '../../utils/AxiosFunctions';
import { connect } from 'react-redux';
import { packageSelector } from '../../../selectors/PackageSelectors';
import * as renderIf from 'render-if';

interface IPublicProps {
  packageResource: string;
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

interface IState {
  data: string;
}

class PackageContainer extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      data: '',
    };
  }

  componentDidMount() {
    this.setState({
      data: JSON.stringify(this.props.packagePayload.packageData, null, '\t'),
    });
  }

  componentWillReceiveProps(nextProps) {
    this.setState({
      data: JSON.stringify(nextProps.packagePayload.packageData, null, '\t'),
    });
  }

  render() {
    return (
      <div>
        {renderIf(this.props.packagePayload)(() => (
          <ViewJsonContent
            descriptor={this.props.packagePayload}
            data={this.state.data}
            usedBy={this.props.packagePayload.usedByBots}
            selectVersion={this.props.selectVersion}
          />
        ))}
      </div>
    );
  }
}

const ComposedPackageContainer: Component<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('PackageContainer'),
  connect(packageSelector),
)(PackageContainer);

export default ComposedPackageContainer;
