import * as React from 'react';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import { IDescriptor } from '../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import BlueButton from '../Assets/Buttons/BlueButton';

const customStyles: { [key: string]: React.CSSProperties } = {
  button: {
    marginLeft: 'auto',
  },
};

interface IState {
  name: string;
  description: string;
}

interface IProps {
  descriptor: IDescriptor;
}

class EditDescriptorModal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      name: this.props.descriptor.name || '',
      description: this.props.descriptor.description || '',
    };
  }

  render() {
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <div style={styles.botHeaderText}>
              {'Edit name and description'}
            </div>
            <BlueButton
              text={'Save'}
              customStyles={customStyles.button}
              disabled={
                this.state.name === this.props.descriptor.name &&
                this.state.description === this.props.descriptor.description
              }
              onClick={() => {
                eddiApiActionDispatchers.updateDescriptorAction(
                  this.props.descriptor.resource,
                  this.state.name,
                  this.state.description,
                );
                ModalActionDispatchers.closeModal();
              }}
            />
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
                onChange={(e) =>
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
const ComposedEditDescriptorModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('EditDescriptorModal'),
)(EditDescriptorModal);

export default ComposedEditDescriptorModal;
