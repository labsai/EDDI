import * as React from 'react';
import Radium from 'radium';
import { CSSProperties } from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import * as moment from 'moment';
import { IPackage } from '../utils/AxiosFunctions';
import { getDate } from '../utils/DateFormat';

const styles: { [key: string]: IExtendedCSSProperties } = {
  content: {
    color: '#16325C',
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
    color: '#54698D',
    fontSize: '12px',
    height: '14px',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    width: 'fit-content',
  },
};

interface IProps {
  packagePayload: IPackage;
}

const PackageDescriptor: React.StatelessComponent<IProps> = (props: IProps) => (
  <div style={styles.descriptors}>
    <div>
      <div style={styles.title}>{'Description'}</div>
      <div style={styles.content}>
        {props.packagePayload.description || 'N/A'}
      </div>
    </div>
    <div style={styles.dateTime}>
      <div style={styles.title}>{'Created'}</div>
      <div style={styles.content}>
        {getDate(props.packagePayload.createdOn)}
      </div>
    </div>
    <div style={styles.dateTime}>
      <div style={styles.title}>{'Last Modified'}</div>
      <div style={styles.content}>
        {getDate(props.packagePayload.lastModifiedOn)}
      </div>
    </div>
  </div>
);

const ComposedPackageDescriptor: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PackageDescriptor'),
)(PackageDescriptor);

export default ComposedPackageDescriptor;
