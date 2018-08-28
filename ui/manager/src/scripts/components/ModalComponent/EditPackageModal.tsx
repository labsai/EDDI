import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IPackage } from '../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';

const customStyles = {
  editPackageButton: {
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    cursor: 'pointer',
    fontSize: '12px',
    height: '36px',
    marginLeft: '60%',
    marginTop: '8px',
    textAlign: 'center',
    minWidth: '100px',
  },
};

interface IState {
  name: string;
  description: string;
}

interface IPublicProps {
  packagePayload: IPackage;
}
interface IPrivateProps extends IPublicProps {
  addedExtensions: string[];
}

class EditPackageModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      name: props.packagePayload.name,
      description: props.packagePayload.description,
    };
  }

  getButtonStyle() {
    if (
      this.state.name === this.props.packagePayload.name &&
      this.state.description === this.props.packagePayload.description
    ) {
      return { ...customStyles.editPackageButton, backgroundColor: '#c4c9d2' };
    } else {
      return {
        ...customStyles.editPackageButton,
        backgroundColor: '#0070D2',
        cursor: 'pointer',
      };
    }
  }
  render() {
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <h2 style={styles.botHeaderText}>{'Edit package'}</h2>
            <button
              style={this.getButtonStyle()}
              disabled={
                this.state.name === this.props.packagePayload.name &&
                this.state.description === this.props.packagePayload.description
              }
              onClick={() => {
                eddiApiActionDispatchers.updateDescriptorAction(
                  this.props.packagePayload.resource,
                  this.state.name,
                  this.state.description,
                );
                ModalActionDispatchers.closeModal();
              }}>
              {'Edit Package'}
            </button>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.botText}>
            {'Name'}
            <div style={styles.inputBoxContent}>
              <textarea
                name={'name'}
                style={styles.inputBoxName}
                defaultValue={this.state.name}
                onChange={e =>
                  this.setState({
                    name: e.target.value,
                  })
                }
              />
            </div>
          </div>
          <div style={styles.botText}>
            {'Description'}
            <div style={styles.inputBoxContent}>
              <textarea
                name={'description'}
                style={styles.inputBox}
                defaultValue={this.state.description}
                onChange={e =>
                  this.setState({
                    description: e.target.value,
                  })
                }
              />
            </div>
          </div>
        </div>
      </div>
    );
  }
}

const ComposedEditPackageModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('Modal'),
)(EditPackageModal);

export default ComposedEditPackageModal;
