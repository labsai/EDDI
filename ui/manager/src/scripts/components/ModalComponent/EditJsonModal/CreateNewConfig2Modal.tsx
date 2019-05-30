import * as React from 'react';
import styles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import AceEditor from 'react-ace';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import Parser from '../../utils/Parser';
import * as _ from 'lodash';
import Editor from './Editor';
import { compileJsonSchema, IJsonError } from '../../utils/helpers/JsonHelpers';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { connect } from 'react-redux';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import { JSONSchema4 } from 'json-schema';
import JsonIsValid from './JsonIsValid';

interface IState {
  editorText: string;
  showExample: boolean;
  errors: IJsonError[];
  isValidJson: boolean;
}

interface IPublicProps {
  type: string;
  name: string;
  description: string;
  data: string;
  onConfirm(): void;
}

interface IPrivateProps extends IPublicProps {
  schema?: JSONSchema4;
}

class CreateNewConfig2Modal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      showExample: false,
      isValidJson: false,
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
      isValidJson: false,
    });
  };

  discardChanges(props = this.props) {
    const editorText = _.isEmpty(props.data) ? '{\n\t\n}' : props.data;
    this.setState({
      editorText: editorText,
    });
  }

  unsavedChanges() {
    // todo: reduxify and refactor editor
    return false;
    /*return (
      this.state.editorText !==
      (_.isEmpty(this.props.data) ? '{\n\t\n}' : this.props.data)
    );*/
  }

  isJsonString() {
    try {
      JSON.parse(this.state.editorText);
    } catch (e) {
      return false;
    }
    return true;
  }

  createNew = () => {
    if (this.validateJson()) {
      eddiApiActionDispatchers.createNewConfigAction(
        this.props.type,
        this.props.name,
        this.props.description,
        this.state.editorText,
      );
      this.props.onConfirm();
    }
  };

  back = () => {
    modalActionDispatchers.showCreateNewConfigModal(
      this.props.type,
      this.props.name,
      this.props.description,
      this.state.editorText,
    );
  };

  onConfirm = () => {
    if (this.validateJson()) {
      this.props.onConfirm();
    }
  };

  validateJson = () => {
    let errors: IJsonError[] = [];
    if (!this.isJsonString()) {
      const jsonParseError: IJsonError = {
        message: 'Error parsing JSON.',
        line: 0,
      };
      errors.push(jsonParseError);
    } else {
      errors = compileJsonSchema(this.props.schema, this.state.editorText);
    }
    const isValidJson = _.isEmpty(errors);
    this.setState({
      errors,
      isValidJson,
    });
    return _.isEmpty(errors);
  };

  render() {
    const typeName = Parser.getPluginName(this.props.type, false);
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <div
              style={
                styles.botHeaderText
              }>{`Edit new ${typeName} JSON data`}</div>
            <div style={styles.modalTopHeaderCenter} />
            {renderIf(this.unsavedChanges())(() => (
              <button
                style={styles.discardChanges}
                onClick={() => this.discardChanges()}>
                {'Discard changes'}
              </button>
            ))}
            <WhiteButton
              onClick={() => this.back()}
              text={'Back'}
              customStyles={styles.backButton}
            />
            <BlueButton
              onClick={this.createNew}
              text={`Create new ${typeName}`}
              disabled={!this.isJsonString()}
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
          onConfirm={this.createNew}
          onChange={this.onChange}
          validate={() => this.validateJson}
        />
      </div>
    );
  }
}

const ComposedCreateNewConfig2Modal: Component<IPrivateProps> = compose<
  IPrivateProps
>(pure, connect(schemaSelector), setDisplayName('CreateNewConfig2Modal'))(
  CreateNewConfig2Modal,
);

export default ComposedCreateNewConfig2Modal;
