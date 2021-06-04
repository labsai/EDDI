import * as React from 'react';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import { createNewBot } from '../utils/AxiosFunctions';
import { historyPush } from '../../history';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';

const customStyles: { [key: string]: React.CSSProperties } = {
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

  async createBot() {
    const botID = await createNewBot(this.state.name, this.state.description);
    eddiApiActionDispatchers.createNewBotAction(botID);
    historyPush(`/botview/${botID}`);
    modalActionDispatchers.closeModal();
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
              onClick={() => this.createBot()}>
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
                onChange={(e) =>
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
                onChange={(e) =>
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

const ComposedCreateBotModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('Modal'),
)(CreateBotModal);

export default ComposedCreateBotModal;
