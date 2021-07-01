import * as React from 'react';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import useStyles from './Loader.styles';

const Loader = () => {
  const classes = useStyles();
  return (
    <div className={classes.loaderContainer}>
      <ClimbingBoxLoader loading color="white" />
    </div>
  );
};

export default Loader;
