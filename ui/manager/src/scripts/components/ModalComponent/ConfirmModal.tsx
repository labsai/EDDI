import * as React from 'react';
import styles from './ConfirmModal.styles';
import './ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import BlueButton from '../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import * as _ from 'lodash';

interface IProps {
  title: string;
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
          <div style={styles.modalTopHeader}>{this.props.title}</div>
        </div>
        <div style={styles.content}>
          {renderIf(!_.isEmpty(this.props.message))(() => (
            <div style={styles.message}>{this.props.message}</div>
          ))}
          <div style={styles.buttons}>
            <BlueButton
              customStyles={styles.buttonMargin}
              text={'Confirm'}
              onClick={this.onClick}
            />
            <WhiteButton
              text={'Cancel'}
              onClick={modalActionDispatchers.closeModal}
            />
          </div>
        </div>
      </div>
    );
  }
}

const ComposedConfirmModal: React.ComponentClass<IProps> = compose<IProps, IProps>(
  pure,
  setDisplayName('Modal'),
)(ConfirmModal);

export default ComposedConfirmModal;
