import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ConfirmModal.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import BlueButton from '../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';

const customStyles = {
  createNewBotButton: {
    backgroundColor: '#0070D2',
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    fontSize: '12px',
    height: '36px',
    marginLeft: '60%',
    marginTop: '8px',
    textAlign: 'center',
    minWidth: '100px',
  },
};

interface IProps {
  message: string;
  onConfirm(): void;
}
class ConfirmModal extends React.Component<IProps> {
  onClick = () => {
    console.log('BUTTON CLICKED');
    this.props.onConfirm();
    modalActionDispatchers.closeModal();
  };
  render() {
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>{this.props.message}</div>
        </div>
        <div style={styles.content}>
          <div style={styles.buttons}>
            <WhiteButton
              customStyles={styles.buttonMargin}
              text={'Cancel'}
              onClick={() => modalActionDispatchers.closeModal}
            />
            <BlueButton text={'Confirm'} onClick={this.onClick} />
          </div>
        </div>
      </div>
    );
  }
}

const ComposedConfirmModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('Modal'),
)(ConfirmModal);

export default ComposedConfirmModal;
