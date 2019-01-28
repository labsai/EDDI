import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import { CSSProperties } from 'react';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';

const styles: CSSProperties = {
  content: {
    ':hover': {
      backgroundColor: '#F4F6F9',
    },
    display: 'inline-flex',
    marginRight: '15px',
    marginTop: '5px',
    paddingTop: '5px',
    paddingBottom: '5px',
    cursor: 'pointer',
  },
  name: {
    color: '#16325C',
    fontSize: '16px',
  },
  smallName: {
    color: '#7A849E',
    marginTop: '3px',
    fontSize: '13px',
  },
  version: {
    color: '#A8B7C7',
    fontSize: '12px',
    marginTop: '4px',
    marginLeft: '5px',
  },
};

interface IProps {
  descriptor: IDetailedDescriptor;
  usedByOlderVersion: boolean;
  isSmallName: boolean;
  onClick(): void;
}

class NameAndVersion extends React.Component<IProps> {
  getNameStyling() {
    return this.props.isSmallName ? styles.smallName : styles.name;
  }

  buttonClick = () => {
    this.props.onClick();
    modalActionDispatchers.closeModal();
  };

  render() {
    return (
      <div style={styles.content} onClick={this.buttonClick}>
        <div style={this.getNameStyling()}>
          {this.props.descriptor.name || this.props.descriptor.id}
        </div>
        <div style={styles.version}>{`v${this.props.descriptor.version}${
          !!this.props.usedByOlderVersion ? '*' : ''
        }`}</div>
      </div>
    );
  }
}

const ComposedNameAndVersion: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('NameAndVersion'),
)(NameAndVersion);

export default ComposedNameAndVersion;
