import * as React from 'react';
import TopBarComponent from '../TopBar/TopBarComponent';
import styles from '../App.style';
import { compose, pure, setDisplayName } from 'recompose';
import PackageList from '../Packages/PackageList';
import { pageEnum } from './pageEnum';

interface IProps {}
interface IState {
  filterText: string;
}

const eddiLogo = require('../../../public/images/eddi-logo.png');

class PackagePage extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      filterText: '',
    };
  }

  filter = (text: string) => {
    this.setState({ filterText: text });
  };

  render() {
    return (
      <div>
        <img src={eddiLogo} style={styles.eddiLogo} />
        <div className="content">
          <TopBarComponent page={pageEnum.package} filter={this.filter} />
          <PackageList filterText={this.state.filterText} />
        </div>
      </div>
    );
  }
}

const ComposedPackagePage: React.ComponentClass = compose<IProps, IState>(
  pure,
  setDisplayName('PackagePage'),
)(PackagePage);

export default ComposedPackagePage;
