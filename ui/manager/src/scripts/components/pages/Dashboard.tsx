import * as React from 'react';
import Botlist from '../Bots/Botlist';
import TopBarComponent from '../TopBar/TopBarComponent';
import styles from '../App.style';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { pageEnum } from './pageEnum';

interface IProps {}
interface IState {
  filterText: string;
}

const eddiLogo = require('../../../public/images/eddi-logo.png');

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
        <img src={eddiLogo} style={styles.eddiLogo} />
        <div className="content">
          <TopBarComponent page={pageEnum.bot} filter={this.filter} />
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
