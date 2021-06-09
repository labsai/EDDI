import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import BlueButton from '../Assets/Buttons/BlueButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import useStyles from './ConfirmModal.styles';
import './ModalComponent.styles.scss';

interface IProps {
  title: string;
  message: string;
  onConfirm(): void;
}
const ConfirmModal = ({ onConfirm, title, message }: IProps) => {
  const classes = useStyles();
  const onClick = () => {
    onConfirm();
    modalActionDispatchers.closeModal();
  };
  return (
    <div>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>{title}</div>
      </div>
      <div className={classes.content}>
        {!_.isEmpty(message) && (
          <div className={classes.message}>{message}</div>
        )}
        <div className={classes.buttons}>
          <BlueButton
            classes={{ button: classes.buttonMargin }}
            text={'Confirm'}
            onClick={onClick}
          />
          <WhiteButton
            text={'Cancel'}
            onClick={modalActionDispatchers.closeModal}
          />
        </div>
      </div>
    </div>
  );
};

const ComposedConfirmModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('Modal'),
)(ConfirmModal);

export default ComposedConfirmModal;
