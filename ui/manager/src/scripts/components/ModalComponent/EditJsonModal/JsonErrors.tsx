import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  MEDIUM_FONT,
  MEDIUM_FONT2,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { IJsonError } from '../../utils/helpers/JsonHelpers';
import { makeStyles } from '@material-ui/core/styles';

const warningIcon = require('../../../../public/images/WarningIcon@3x.png');

const useStyles = makeStyles({
  content: {
    marginLeft: '20px',
  },
  header: {
    display: 'flex',
  },
  errorTitle: {
    color: RED_COLOR,
    fontSize: MEDIUM_FONT2,
  },
  errorContainer: {
    marginTop: '8px',
  },
  error: {
    display: 'flex',
    fontSize: MEDIUM_FONT,
  },
  key: {
    color: WHITE_COLOR,
    minWidth: '120px',
  },
  errorMessage: {
    color: RED_COLOR,
    fontWeight: 'bold',
  },
  errorSchemaPath: {
    color: RED_COLOR,
  },
  warningIcon: {
    height: '22px',
    marginRight: '5px',
  },
});

interface IProps {
  errors: IJsonError[];
}

const JsonErrors: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.content}>
      <div className={classes.header}>
        <img src={warningIcon} className={classes.warningIcon} />
        <div
          className={
            classes.errorTitle
          }>{`Found ${props.errors.length} Error(s):`}</div>
      </div>
      <div>
        {props.errors.map((error, i) => (
          <div className={classes.errorContainer} key={i}>
            <div className={classes.error}>
              <div className={classes.key}>{'Location:'}</div>
              <div className={classes.errorMessage}>{`ERROR at line: ${
                error.line + 1
              }`}</div>
            </div>
            <div className={classes.error}>
              <div className={classes.key}>{'Message:'}</div>
              <div
                className={classes.errorSchemaPath}>{`${error.message}`}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

const ComposedJsonErrors: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('JsonErrors'),
)(JsonErrors);

export default ComposedJsonErrors;
