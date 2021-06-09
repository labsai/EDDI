import * as React from 'react';
import useStyles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import { ModalEnum } from '../utils/ModalEnum';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';

interface IProps {
  setName: (name: string) => void;
  setDescription: (description: string) => void;
}

const CreatePackageModal = ({ setName, setDescription }: IProps) => {
  const [packageName, setPackageName] = React.useState('');
  const [packageDescription, setPackageDescription] = React.useState('');

  const classes = useStyles();

  const getButtonStyle = () => {
    if (!packageName) {
      return { backgroundColor: '#c4c9d2' };
    } else {
      return {
        backgroundColor: '#0070D2',
        cursor: 'pointer',
      };
    }
  };

  const openModal = () => {
    setName(packageName);
    setDescription(packageDescription);
    ModalActionDispatchers.showModal(ModalEnum.updatePackage, null, null);
  };

  return (
    <div>
      <div className={classes.tallModalHeader}>
        <div className={classes.modalTopHeader}>
          <h2 className={classes.createPackageHeaderText}>
            {'Create new Package'}
          </h2>
          <div className={classes.modalTopHeaderCenter} />
          <button
            disabled={!packageName}
            onClick={openModal}
            style={getButtonStyle()}
            className={classes.createNewBotButton}>
            {'Create Package'}
          </button>
        </div>
      </div>
      <div className={classes.content}>
        <div className={classes.botText}>
          {'Give the package a name'}
          <div className={classes.inputBoxContent}>
            <textarea
              defaultValue={''}
              name={'packageName'}
              className={classes.inputBoxName}
              placeholder={'Give the package a name..'}
              onChange={(e) => setPackageName(e.target.value)}
            />
          </div>
        </div>
        <div className={classes.botText}>
          {'Give the package a short description'}
          <div className={classes.inputBoxContent}>
            <textarea
              defaultValue={''}
              name={'packageDescription'}
              className={classes.inputBox}
              placeholder={'Give the package a short description..'}
              onChange={(e) => setPackageDescription(e.target.value)}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

const ComposedCreatePackageModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('Modal'),
)(CreatePackageModal);

export default ComposedCreatePackageModal;
