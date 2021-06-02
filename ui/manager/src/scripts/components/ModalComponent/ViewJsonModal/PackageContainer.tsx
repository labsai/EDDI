import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
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

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.setState({
        data: JSON.stringify(this.props.packagePayload.packageData, null, '\t'),
      });
    }
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

const ComposedPackageContainer: React.ComponentClass<IPublicProps> = compose<IPrivateProps, IPublicProps>(
  pure,
  setDisplayName('PackageContainer'),
  connect(packageSelector),
)(PackageContainer);

export default ComposedPackageContainer;
