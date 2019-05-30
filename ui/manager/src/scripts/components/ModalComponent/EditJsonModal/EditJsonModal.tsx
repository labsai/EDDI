import * as React from 'react';
import styles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import BlueButton from '../../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import { compileJsonSchema, IJsonError } from '../../utils/helpers/JsonHelpers';
import Editor from './Editor';
import 'brace';
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import JsonIsValid from './JsonIsValid';
import { connect } from 'react-redux';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { JSONSchema4 } from 'json-schema';

interface IState {
  editorText: string;
  showExample: boolean;
  errors: IJsonError[];
  isValidJson: boolean;
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
      isValidJson: false,
    };
  }

  componentDidMount() {
    console.log(this.props);
    this.discardChanges();
  }

  componentWillReceiveProps(nextProps) {
    this.discardChanges(nextProps);
  }

  onChange = value => {
    this.setState({
      editorText: value,
      isValidJson: false,
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

  validateJson = (props = this.props, state = this.state) => {
    let errors: IJsonError[] = [];
    if (!this.isJsonString()) {
      const jsonParseError: IJsonError = {
        message: 'Error parsing JSON.',
        line: 0,
      };
      errors.push(jsonParseError);
    } else {
      errors = compileJsonSchema(props.schema, state.editorText);
    }
    const isValidJson = _.isEmpty(errors);
    this.setState({
      errors,
      isValidJson,
    });
    return isValidJson;
  };

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
        {renderIf(this.state.isValidJson)(() => <JsonIsValid />)}
        <JsonExample type={this.props.type} />
        <Editor
          type={this.props.type}
          data={this.state.editorText}
          errors={this.state.errors}
          onConfirm={this.updateJson}
          onChange={this.onChange}
          validate={this.validateJson}
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
