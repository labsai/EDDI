import * as React from 'react';
import Botlist from '../Bots/Botlist';
import TopBarComponent from '../TopBar/TopBarComponent';
import styles from '../App.style';
import { Component, compose, pure, setDisplayName } from 'recompose';

interface IProps {}
interface IState {
  filterText: string;
}

const differLogo = require('../../../public/images/DifferSymbolSmall.png');

class Dashboard extends React.Component<IProps, IState> {
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
        <img src={differLogo} style={styles.differLogo} />
        <div className="content">
          <TopBarComponent filter={this.filter} />
          <Botlist filterText={this.state.filterText} />
        </div>
      </div>
    );
  }
}

const ComposedDashboard: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(pure, setDisplayName('Dashboard'))(Dashboard);

export default ComposedDashboard;
