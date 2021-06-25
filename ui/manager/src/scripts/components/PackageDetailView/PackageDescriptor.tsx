import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  DARK_GREY_COLOR,
  GREY_COLOR,
} from '../../../styles/DefaultStylingProperties';
import { IPackage } from '../utils/AxiosFunctions';
import { getDate } from '../utils/DateFormat';

const useStyles = makeStyles({
  content: {
    color: DARK_GREY_COLOR,
    fontSize: '13px',
    maxWidth: '500px',
    textAlign: 'left',
  },
  dateTime: {
    marginLeft: '65px',
  },
  descriptors: {
    display: 'flex',
    marginTop: '40px',
    overflow: 'hidden',
    width: '100%',
  },
  title: {
    color: GREY_COLOR,
    fontSize: '12px',
    height: '14px',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    width: 'fit-content',
  },
});

interface IProps {
  packagePayload: IPackage;
}

const PackageDescriptor: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div className={classes.descriptors}>
      <div>
        <div className={classes.title}>{'Description'}</div>
        <div className={classes.content}>
          {props.packagePayload.description || 'N/A'}
        </div>
      </div>
      <div className={classes.dateTime}>
        <div className={classes.title}>{'Created'}</div>
        <div className={classes.content}>
          {getDate(props.packagePayload.createdOn)}
        </div>
      </div>
      <div className={classes.dateTime}>
        <div className={classes.title}>{'Last Modified'}</div>
        <div className={classes.content}>
          {getDate(props.packagePayload.lastModifiedOn)}
        </div>
      </div>
    </div>
  );
};

const ComposedPackageDescriptor: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PackageDescriptor'),
)(PackageDescriptor);

export default ComposedPackageDescriptor;
