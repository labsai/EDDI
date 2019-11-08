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
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import JsonIsValid from './JsonIsValid';
import { connect } from 'react-redux';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { JSONSchema4 } from 'json-schema';
import editStyles from './EditJsonModal.styles';
import JsonSchemaForm from './JsonSchemaForm/JsonSchemaForm';
import { getTypeFromResource } from '../../utils/ApiFunctions';

enum TabEnum {
  'editor',
  'form',
}

interface IState {
  editorText: string;
  showExample: boolean;
  errors: IJsonError[];
  isValidJson: boolean;
  selectedTab: TabEnum;
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
  // todo: reduxify this component and editor
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      showExample: false,
      errors: [],
      isValidJson: false,
      selectedTab: TabEnum.editor,
    };
  }

  componentDidMount() {
    eddiApiActionDispatchers.fetchJsonSchemaAction(
      getTypeFromResource(this.props.resource),
    );
    this.discardChanges();
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.discardChanges();
    }
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
    // todo: reduxify and refactor editor
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

  validateSchemaForm = () => {
    this.validateJson();
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
        <div style={editStyles.tabs}>
          <div
            style={
              this.state.selectedTab === TabEnum.editor
                ? editStyles.tab
                : { ...editStyles.tab, ...editStyles.tabDisabled }
            }
            onClick={() => this.setState({ selectedTab: TabEnum.editor })}>
            {'Editor'}
          </div>
          <div
            style={
              this.state.selectedTab === TabEnum.form
                ? editStyles.tab
                : { ...editStyles.tab, ...editStyles.tabDisabled }
            }
            onClick={() =>
              this.validateJson()
                ? this.setState({ selectedTab: TabEnum.form })
                : this.validateJson()
            }>
            {'Form'}
          </div>
        </div>
        {renderIf(this.state.isValidJson)(() => <JsonIsValid />)}
        {renderIf(this.state.selectedTab === TabEnum.editor)(() => (
          <div>
            {renderIf(!_.isEmpty(this.state.errors))(() => (
              <JsonErrors errors={this.state.errors} />
            ))}
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
        ))}
        {renderIf(this.state.selectedTab === TabEnum.form)(() => (
          <JsonSchemaForm
            schema={this.props.schema}
            data={this.state.editorText}
            onChange={this.onChange}
            validate={this.validateSchemaForm}
          />
        ))}
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
