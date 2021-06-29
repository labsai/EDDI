import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import useStyles from '../App.style';
import Botlist from '../Bots/Botlist';
import TopBarComponent from '../TopBar/TopBarComponent';
import { pageEnum } from './pageEnum';

const eddiLogo = require('../../../public/images/eddi-logo-white.svg');

const Dashboard = () => {
  const [filterText, setFilterText] = React.useState('');

  const classes = useStyles();

  const filter = (text: string) => {
    setFilterText(text);
  };

  return (
    <div>
      <img src={eddiLogo} className={classes.eddiLogo} />
      <div className="content">
        <TopBarComponent page={pageEnum.bot} filter={filter} />
        <Botlist filterText={filterText} />
      </div>
    </div>
  );
};

const ComposedDashboard = compose(pure, setDisplayName('Dashboard'))(Dashboard);

export default ComposedDashboard;
