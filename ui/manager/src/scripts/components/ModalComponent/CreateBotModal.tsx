import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { createNewBot } from '../utils/AxiosFunctions';

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

interface IState {
  name: string;
  description: string;
}

interface IProps {}
class CreateBotModal extends React.Component<IProps, IState> {
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
  render() {
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <h2 style={styles.botHeaderText}>{'Create new bot'}</h2>
            <button
              disabled={!this.state.name}
              style={this.getButtonStyle()}
              onClick={async () => {
                const botID = await createNewBot(
                  this.state.name,
                  this.state.description,
                );
                location.href = `/botview/${botID}`;
              }}>
              {'Create bot'}
            </button>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.botText}>
            {'Give the bot a name'}
            <div style={styles.inputBoxContent}>
              <textarea
                name={'name'}
                defaultValue={''}
                style={styles.inputBoxName}
                placeholder={'Give the bot a name..'}
                onChange={e =>
                  this.setState({
                    name: e.target.value,
                  })
                }
              />
            </div>
          </div>
          <div style={styles.botText}>
            {'Give the bot a short description'}
            <div style={styles.inputBoxContent}>
              <textarea
                name={'description'}
                style={styles.inputBox}
                defaultValue={''}
                placeholder={'Give the bot a short description..'}
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

const ComposedCreateBotModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('Modal'),
)(CreateBotModal);

export default ComposedCreateBotModal;
