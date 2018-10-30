import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ErrorMessageModal.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';

interface IProps {
  title: string;
  message: string;
}

const warningIcon = require('../../../public/images/WarningIcon@3x.png');

class ErrorMessageModal extends React.Component<IProps> {
  onClick = () => {
    modalActionDispatchers.closeModal();
  };
  render() {
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <div style={styles.errorTitle}>{this.props.title}</div>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.topContent}>
            <img src={warningIcon} style={styles.warningIcon} />
            <div style={styles.message}>{this.props.message}</div>
          </div>
          <div style={styles.button}>
            <WhiteButton
              text={'OK'}
              onClick={modalActionDispatchers.closeModal}
            />
          </div>
        </div>
      </div>
    );
  }
}

const ComposedErrorMessageModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('ErrorMessageModal'),
)(ErrorMessageModal);

export default ComposedErrorMessageModal;
