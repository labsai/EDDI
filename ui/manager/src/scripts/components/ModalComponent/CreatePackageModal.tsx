import * as React from 'react';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import { ModalEnum } from '../utils/ModalEnum';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';

const customStyles: { [key: string]: React.CSSProperties } = {
  createNewBotButton: {
    backgroundColor: '#0070D2',
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
  packageName: string;
  packageDescription: string;
}

interface IProps {
  setName: (name: string) => void;
  setDescription: (description: string) => void;
}

class CreatePackageModal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      packageName: '',
      packageDescription: '',
    };
  }

  getButtonStyle() {
    if (!this.state.packageName) {
      return { ...customStyles.createNewBotButton, backgroundColor: '#c4c9d2' };
    } else {
      return {
        ...customStyles.createNewBotButton,
        backgroundColor: '#0070D2',
        cursor: 'pointer',
      };
    }
  }

  openModal = () => {
    this.props.setName(this.state.packageName);
    this.props.setDescription(this.state.packageDescription);
    ModalActionDispatchers.showModal(ModalEnum.updatePackage, null, null);
  };

  render() {
    return (
      <div>
        <div style={styles.tallModalHeader}>
          <div style={styles.modalTopHeader}>
            <h2 style={styles.createPackageHeaderText}>
              {'Create new Package'}
            </h2>
            <div style={styles.modalTopHeaderCenter} />
            <button
              disabled={!this.state.packageName}
              onClick={() => {
                this.openModal();
              }}
              style={this.getButtonStyle()}>
              {'Create Package'}
            </button>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.botText}>
            {'Give the package a name'}
            <div style={styles.inputBoxContent}>
              <textarea
                defaultValue={''}
                name={'packageName'}
                style={styles.inputBoxName}
                placeholder={'Give the package a name..'}
                onChange={(e) =>
                  this.setState({
                    packageName: e.target.value,
                  })
                }
              />
            </div>
          </div>
          <div style={styles.botText}>
            {'Give the package a short description'}
            <div style={styles.inputBoxContent}>
              <textarea
                defaultValue={''}
                name={'packageDescription'}
                style={styles.inputBox}
                placeholder={'Give the package a short description..'}
                onChange={(e) =>
                  this.setState({
                    packageDescription: e.target.value,
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

const ComposedCreatePackageModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('Modal'),
)(CreatePackageModal);

export default ComposedCreatePackageModal;
