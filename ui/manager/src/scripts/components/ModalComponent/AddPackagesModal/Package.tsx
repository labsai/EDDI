import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { IPackage } from '../../utils/AxiosFunctions';
import { packageSelector } from '../../../selectors/PackageSelectors';
import * as moment from 'moment';
import * as renderIf from 'render-if';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import TruncateTextComponent from '../../Assets/TruncateTextComponent';
import * as _ from 'lodash';
import { connect } from 'react-redux';
import styles from './Package.styles';
import BotsUsingPackage from '../../PackageDetailView/UsedByComponent/BotsUsingPackage';

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

class Package extends React.Component<IPrivateProps> {
  getButtonStyle() {
    if (this.props.selected) {
      return { ...styles.button, backgroundColor: '#4BCA81' };
    } else {
      return {
        ...styles.button,
      };
    }
  }
  getNameStyle() {
    if (this.props.selected) {
      return { ...styles.packageName, color: '#16325C' };
    } else {
      return {
        ...styles.packageName,
      };
    }
  }

  handleClick = () => {
    this.props.handleClick(this.props.packagePayload.resource);
  };

  selectVersion = (newVersion: number) => {
    if (this.props.selected) {
      this.handleClick();
    }
    this.props.selectVersion(this.props.packagePayload.resource, newVersion);
  };

  render() {
    return (
      <div>
        {renderIf(!this.props.packagePayload)(() => (
          <div>
            {renderIf(this.props.isLoading)(() => (
              <p>{'Loading package'}</p>
            ))}
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load package'}</p>
            ))}
            {renderIf(!this.props.isLoading && !this.props.error)(() => (
              <p>{'This package does not exist'}</p>
            ))}
          </div>
        ))}
        {renderIf(this.props.packagePayload)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load package'}</p>
            ))}
            {renderIf(
              !this.props.error && _.isEmpty(this.props.packagePayload),
            )(() => (
              <p>{'This package does not exist'}</p>
            ))}
            {renderIf(
              !this.props.error && !_.isEmpty(this.props.packagePayload),
            )(() => (
              <div style={styles.content}>
                <div style={styles.topContent}>
                  <button
                    onClick={this.handleClick}
                    style={this.getButtonStyle()}>{`${
                    this.props.selected ? '\u2714' : '+'
                  }`}</button>
                  <div style={this.getNameStyle()}>
                    {this.props.packagePayload.name === ''
                      ? this.props.packagePayload.id
                      : this.props.packagePayload.name}
                  </div>
                  <div style={styles.versionSelect}>
                    <VersionSelectComponent
                      currentVersion={this.props.packagePayload.currentVersion}
                      selectedVersion={this.props.packagePayload.version}
                      selectVersion={this.selectVersion}
                    />
                  </div>
                  <div style={styles.centerFlex} />
                  <div style={styles.modifiedDate}>
                    {moment(this.props.packagePayload.lastModifiedOn).format(
                      'DD.MM.YYYY',
                    )}
                  </div>
                </div>
                <div style={styles.bottomContent}>
                  <TruncateTextComponent
                    text={this.props.packagePayload.description}
                    length={80}
                  />
                  <BotsUsingPackage
                    packagePayload={this.props.packagePayload}
                    isSmallName={true}
                  />
                </div>
              </div>
            ))}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedPackage: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(packageSelector),
  setDisplayName('Package'),
)(Package);

export default ComposedPackage;
