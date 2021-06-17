import * as React from 'react';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import useStyles from './Loader.styles';

const Loader = () => {
  const classes = useStyles();
  return (
    <div className={classes.loaderContainer}>
      <ClimbingBoxLoader loading />
    </div>
  );
};

export default Loader;
