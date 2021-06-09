import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { packageSelector } from '../../../selectors/PackageSelectors';
import TruncateTextComponent from '../../Assets/TruncateTextComponent';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import BotsUsingPackage from '../../PackageDetailView/UsedByComponent/BotsUsingPackage';
import { IPackage } from '../../utils/AxiosFunctions';
import useStyles from './Package.styles';

interface IPublicProps {
  packageResource: string;
  selected: boolean;
  handleClick(resource: string): void;
  selectVersion(resource: string, newVersion): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  packagePayload: IPackage;
  isLoading: boolean;
}

const Package = (props: IPrivateProps) => {
  const classes = useStyles();

  const handleClick = () => {
    props.handleClick(props.packagePayload.resource);
  };

  const selectVersion = (newVersion: number) => {
    if (props.selected) {
      handleClick();
    }
    props.selectVersion(props.packagePayload.resource, newVersion);
  };

  return (
    <div>
      {!props.packagePayload && (
        <div>
          {props.isLoading && <p>{'Loading package'}</p>}
          {!!props.error && <p>{'Error: Could not load package'}</p>}
          {!props.isLoading && !props.error && (
            <p>{'This package does not exist'}</p>
          )}
        </div>
      )}
      {!!props.packagePayload && (
        <div>
          {!!props.error && <p>{'Error: Could not load package'}</p>}
          {!props.error && _.isEmpty(props.packagePayload) && (
            <p>{'This package does not exist'}</p>
          )}
          {!props.error && !_.isEmpty(props.packagePayload) && (
            <div className={classes.content}>
              <div className={classes.topContent}>
                <button
                  onClick={handleClick}
                  style={{
                    backgroundColor: props.selected ? '#4BCA81' : undefined,
                  }}
                  className={classes.button}>{`${
                  props.selected ? '\u2714' : '+'
                }`}</button>
                <div
                  style={{ color: props.selected ? '#16325C' : undefined }}
                  className={classes.packageName}>
                  {props.packagePayload.name === ''
                    ? props.packagePayload.id
                    : props.packagePayload.name}
                </div>
                <div className={classes.versionSelect}>
                  <VersionSelectComponent
                    currentVersion={props.packagePayload.currentVersion}
                    selectedVersion={props.packagePayload.version}
                    selectVersion={selectVersion}
                  />
                </div>
                <div className={classes.centerFlex} />
                <div className={classes.modifiedDate}>
                  {moment(props.packagePayload.lastModifiedOn).format(
                    'DD.MM.YYYY',
                  )}
                </div>
              </div>
              <div className={classes.bottomContent}>
                <TruncateTextComponent
                  text={props.packagePayload.description}
                  length={80}
                />
                <BotsUsingPackage
                  packagePayload={props.packagePayload}
                  isSmallName={true}
                />
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const ComposedPackage: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(packageSelector),
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
