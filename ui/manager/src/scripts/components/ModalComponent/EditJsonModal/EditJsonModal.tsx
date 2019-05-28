import * as React from 'react';
import styles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import BlueButton from '../../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import { compileJsonSchema, IJsonError } from '../../utils/helpers/JsonHelpers';
import { DICTIONARY_SCHEMA } from '../../utils/JsonSchemas/JsonSchemas';
import Editor from './Editor';
import 'brace';
import { getTypeFromResource, getTypePath } from '../../utils/ApiFunctions';
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import { connect } from 'react-redux';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { JSONSchema4 } from 'json-schema';

interface IState {
  editorText: string;
  showExample: boolean;
  errors: IJsonError[];
}

interface IPrivateProps extends IPublicProps {
  resource: string;
  data: string;
  type: string;
}

interface IPublicProps {
  schema?: JSONSchema4;
}

class EditJsonModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      showExample: false,
      errors: [],
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
    // todo: reduxify editor
    return false;
    /*
    return this.state.editorText !== this.props.data;
    */
  }

  updateJson = () => {
    if (this.validateJson()) {
      eddiApiActionDispatchers.updateJsonDataAction(
        this.props.resource,
        JSON.parse(this.state.editorText),
      );
      modalActionDispatchers.closeModal();
    }
  };

  isJsonString() {
    try {
      JSON.parse(this.state.editorText);
    } catch (e) {
      return false;
    }
    return true;
  }

  validateJson(): boolean {
    const errors = compileJsonSchema(this.props.schema, this.state.editorText);
    this.setState({
      errors,
    });
    return _.isEmpty(errors);
  }

  render() {
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
        {renderIf(!_.isEmpty(this.state.errors))(() => (
          <JsonErrors errors={this.state.errors} />
        ))}
        <JsonExample type={this.props.type} />
        <Editor
          type={this.props.type}
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
>(pure, connect(schemaSelector), setDisplayName('EditJsonModal'))(
  EditJsonModal,
);

export default ComposedEditJsonModal;
