import * as React from 'react';
import styles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import BlueButton from '../../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import { compileJsonSchema } from '../../utils/helpers/JsonHelpers';
import { DICTIONARY_SCHEMA } from '../../utils/JsonSchemas/JsonSchemas';
import Editor from './Editor';
import 'brace';
import { getTypeFromResource, getTypePath } from '../../utils/ApiFunctions';
import { DICTIONARY, REGULAR_DICTIONARY } from '../../utils/EddiTypes';

interface IState {
  editorText: string;
}

interface IPrivateProps extends IPublicProps {}

interface IPublicProps {
  resource: string;
  data: string;
}

class EditJsonModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
    };
  }

  componentDidMount() {
    this.discardChanges();
  }

  componentWillReceiveProps(nextProps) {
    this.discardChanges(nextProps);
  }

  onChange = value => {
    this.setState({
      editorText: value,
    });
  };

  discardChanges(props = this.props) {
    this.setState({
      editorText: props.data,
    });
  }

  unsavedChanges() {
    return this.state.editorText !== this.props.data;
  }

  updateJson = () => {
    eddiApiActionDispatchers.updateJsonDataAction(
      this.props.resource,
      JSON.parse(this.state.editorText),
    );
    modalActionDispatchers.closeModal();
  };

  isJsonString() {
    try {
      JSON.parse(this.state.editorText);
    } catch (e) {
      return false;
    }
    return true;
  }

  validateJson() {
    compileJsonSchema(DICTIONARY_SCHEMA, JSON.parse(this.state.editorText));
  }

  render() {
    const type = getTypeFromResource(this.props.resource);
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <div style={styles.botHeaderText}>{'Edit existing data'}</div>
            <div style={styles.modalTopHeaderCenter} />
            {renderIf(this.unsavedChanges())(() => (
              <button
                style={styles.discardChanges}
                onClick={() => this.validateJson()}>
                {'Discard changes'}
              </button>
            ))}
            <BlueButton
              onClick={this.updateJson}
              disabled={!this.unsavedChanges || !this.isJsonString()}
              text={'Save changes'}
            />
          </div>
        </div>
        <Editor
          type={type}
          data={this.state.editorText}
          onConfirm={this.updateJson}
          onChange={this.onChange}
        />
      </div>
    );
  }
}

const ComposedEditJsonModal: Component<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(pure, setDisplayName('EditJsonModal'))(EditJsonModal);

export default ComposedEditJsonModal;
