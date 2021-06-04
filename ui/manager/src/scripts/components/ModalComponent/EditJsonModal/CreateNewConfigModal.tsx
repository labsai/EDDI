import * as React from 'react';
import styles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import Parser from '../../utils/Parser';

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
  name: string;
  description: string;
}

interface IProps {
  name: string;
  description: string;
  type: string;
  data: string;
  onConfirm(): void;
}

class CreateNewConfigModal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      name: props.name || '',
      description: props.description || '',
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

  nextButton = () => {
    ModalActionDispatchers.showCreateNewConfig2Modal(
      this.props.type,
      this.state.name,
      this.state.description,
      this.props.data,
      this.props.onConfirm,
    );
  };

  render() {
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
                this.nextButton();
              }}
              style={this.getButtonStyle()}>
              {`Next`}
            </button>
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.botText}>
            {`Give the ${typeName} a name`}
            <div style={styles.inputBoxContent}>
              <textarea
                defaultValue={this.state.name}
                name={'name'}
                style={styles.inputBoxName}
                placeholder={`Give the ${typeName} a name..`}
                onChange={(e) =>
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
                defaultValue={this.state.description}
                name={'description'}
                style={styles.inputBox}
                placeholder={`Give the ${typeName} a short description..`}
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

const ComposedCreateNewConfigModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('CreateNewConfigModal'),
)(CreateNewConfigModal);

export default ComposedCreateNewConfigModal;
