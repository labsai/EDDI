import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { GREEN_COLOR } from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  content: {
    display: 'flex',
    color: GREEN_COLOR,
    paddingTop: '10px',
    paddingBottom: '10px',
  },
  icon: {
    marginLeft: '20px',
    height: '26px',
    width: '26px',
  },
  text: {
    marginLeft: '4px',
    marginTop: '2px',
    fontSize: '18px',
  },
});

const JsonIsValid: React.StatelessComponent = () => {
  const classes = useStyles();
  return (
    <div className={classes.content}>
      <FontAwesomeIcon
        className={classes.icon}
        icon={['fas', 'check-circle']}
        color={GREEN_COLOR}
      />
      <div className={classes.text}>{'Found no errors. JSON is valid.'}</div>
    </div>
  );
};

interface IProps {}

const ComposedJsonIsValid: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('JsonIsValid'),
)(JsonIsValid);

export default ComposedJsonIsValid;
