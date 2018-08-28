import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IBot } from '../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';

const customStyles = {
  editBotButton: {
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
  bot: IBot;
}

class EditBotModal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      name: this.props.bot.name || '',
      description: this.props.bot.description || '',
    };
  }

  getButtonStyle() {
    if (
      this.state.name === this.props.bot.name &&
      this.state.description === this.props.bot.description
    ) {
      return { ...customStyles.editBotButton, backgroundColor: '#c4c9d2' };
    } else {
      return {
        ...customStyles.editBotButton,
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
            <h2 style={styles.botHeaderText}>{'Edit bot'}</h2>
            <button
              style={this.getButtonStyle()}
              disabled={
                this.state.name === this.props.bot.name &&
                this.state.description === this.props.bot.description
              }
              onClick={() => {
                eddiApiActionDispatchers.updateDescriptorAction(
                  this.props.bot.resource,
                  this.state.name,
                  this.state.description,
                );
                ModalActionDispatchers.closeModal();
              }}>
              {'Edit bot'}
            </button>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.botText}>
            {'Name'}
            <div style={styles.inputBoxContent}>
              <textarea
                defaultValue={this.state.name}
                name={'name'}
                style={styles.inputBoxName}
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
                defaultValue={this.state.description}
                name={'description'}
                style={styles.inputBox}
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
const ComposedEditBotModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('Modal'),
)(EditBotModal);

export default ComposedEditBotModal;
