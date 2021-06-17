import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import useStyles from '../App.style';
import PackageList from '../Packages/PackageList';
import TopBarComponent from '../TopBar/TopBarComponent';
import { pageEnum } from './pageEnum';

const eddiLogo = require('../../../public/images/eddi-logo.png');

const PackagePage = () => {
  const [filterText, setFilterText] = React.useState('');

  const classes = useStyles();

  const filter = (text: string) => {
    setFilterText(text);
  };

  return (
    <div>
      <img src={eddiLogo} className={classes.eddiLogo} />
      <div className="content">
        <TopBarComponent page={pageEnum.package} filter={filter} />
        <PackageList filterText={filterText} />
      </div>
    </div>
  );
};

const ComposedPackagePage = compose(
  pure,
  setDisplayName('PackagePage'),
)(PackagePage);

export default ComposedPackagePage;
