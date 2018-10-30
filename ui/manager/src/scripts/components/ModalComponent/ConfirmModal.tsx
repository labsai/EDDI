import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ConfirmModal.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import BlueButton from '../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';

interface IProps {
  message: string;
  onConfirm(): void;
}
class ConfirmModal extends React.Component<IProps> {
  onClick = () => {
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
              onClick={modalActionDispatchers.closeModal}
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
