import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import { packageSelector } from '../../../selectors/PackageSelectors';
import * as moment from 'moment';
import TruncateTextComponent from '../../Assets/TruncateTextComponent';
import * as _ from 'lodash';
import { connect } from 'react-redux';
import styles from '../AddPackagesModal/Package.styles';

interface IProps {
  descriptor: IDetailedDescriptor;
  selected: boolean;
  handleClick(resource: string): void;
}

class SelectableConfig extends React.Component<IProps> {
  getNameStyle() {
    if (this.props.selected) {
      return { ...styles.packageName, color: '#16325C' };
    } else {
      return {
        ...styles.packageName,
      };
    }
  }

  getButtonStyle() {
    if (this.props.selected) {
      return { ...styles.button, backgroundColor: '#4BCA81' };
    } else {
      return {
        ...styles.button,
      };
    }
  }

  handleClick = () => {
    this.props.handleClick(this.props.descriptor.resource);
  };

  render() {
    return (
      <div>
        <div style={styles.content}>
          <div style={styles.topContent}>
            <button
              onClick={this.handleClick}
              style={this.getButtonStyle()}>{`${
              this.props.selected ? '\u2714' : '+'
            }`}</button>
            <div style={this.getNameStyle()}>{this.props.descriptor.name}</div>
            <div style={styles.versionName}>
              {`V${this.props.descriptor.version}`}
            </div>
            <div style={styles.centerFlex} />
            <div style={styles.modifiedDate}>
              {moment(this.props.descriptor.lastModifiedOn).format(
                'DD.MM.YYYY',
              )}
            </div>
          </div>
          <div style={styles.bottomContent}>
            <TruncateTextComponent
              text={this.props.descriptor.description}
              length={80}
            />
          </div>
        </div>
      </div>
    );
  }
}

const ComposedSelectableConfig: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('SelectableConfig'),
)(SelectableConfig);

export default ComposedSelectableConfig;
