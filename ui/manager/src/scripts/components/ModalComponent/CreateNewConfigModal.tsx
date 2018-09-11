import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { ModalEnum } from '../utils/ModalEnum';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import Parser from '../utils/Parser';

const customStyles = {
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
  name: string;
  description: string;
}

interface IProps {
  setName: (name: string) => void;
  setDescription: (description: string) => void;
  type: string;
}

class CreateNewConfigModal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      name: '',
      description: '',
    };
  }

  getButtonStyle() {
    if (!this.state.name) {
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
    this.props.setName(this.state.name);
    this.props.setDescription(this.state.description);
    // todo: ModalActionDispatchers.showModal(ModalEnum.updatePackage, null, null);
  };

  render() {
    console.log(this.props);
    const typeName = Parser.getPluginName(this.props.type, false);
    return (
      <div>
        <div style={styles.tallModalHeader}>
          <div style={styles.modalTopHeader}>
            <h2 style={styles.createPackageHeaderText}>
              {`Create new ${typeName}`}
            </h2>
            <div style={styles.modalTopHeaderCenter} />
            <button
              disabled={!this.state.name}
              onClick={() => {
                this.openModal();
              }}
              style={this.getButtonStyle()}>
              {`Create ${typeName}`}
            </button>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.botText}>
            {`Give the ${typeName} a name`}
            <div style={styles.inputBoxContent}>
              <textarea
                defaultValue={''}
                name={'name'}
                style={styles.inputBoxName}
                placeholder={`Give the ${typeName} a name..`}
                onChange={e =>
                  this.setState({
                    name: e.target.value,
                  })
                }
              />
            </div>
          </div>
          <div style={styles.botText}>
            {`Give the ${typeName} a short description`}
            <div style={styles.inputBoxContent}>
              <textarea
                defaultValue={''}
                name={'description'}
                style={styles.inputBox}
                placeholder={`Give the ${typeName} a short description..`}
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

const ComposedCreateNewConfigModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('CreateNewConfigModal'),
)(CreateNewConfigModal);

export default ComposedCreateNewConfigModal;
