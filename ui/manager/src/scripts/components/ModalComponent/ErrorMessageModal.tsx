import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import useStyles from './ErrorMessageModal.styles';
import './ModalComponent.styles.scss';

interface IProps {
  title: string;
  message: string;
}

const warningIcon = require('../../../public/images/WarningIcon@3x.png');

const ErrorMessageModal = ({ title, message }: IProps) => {
  const onClick = () => {
    modalActionDispatchers.closeModal();
  };
  const classes = useStyles();
  return (
    <div>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          <div className={classes.errorTitle}>{title}</div>
        </div>
      </div>
      <div className={classes.content}>
        <div className={classes.topContent}>
          <img src={warningIcon} className={classes.warningIcon} />
          <div className={classes.message}>{message}</div>
        </div>
        <div className={classes.button}>
          <WhiteButton
            text={'OK'}
            onClick={modalActionDispatchers.closeModal}
          />
        </div>
      </div>
    </div>
  );
};

const ComposedErrorMessageModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ErrorMessageModal'),
)(ErrorMessageModal);

export default ComposedErrorMessageModal;
