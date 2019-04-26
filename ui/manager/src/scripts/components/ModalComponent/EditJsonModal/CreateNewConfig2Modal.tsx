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
import { getPostExample } from '../../utils/EddiConfigExampleData';
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import { DICTIONARY_SCHEMA } from '../../utils/JsonSchemas/JsonSchemas';
import { compileJsonSchema, IJsonError } from '../../utils/helpers/JsonHelpers';
import * as AE from 'react-ace';
import * as Ajv from 'ajv';
import * as Jsm from 'json-source-map';

require('brace/mode/json');
require('brace/theme/monokai');

interface IState {
  editorText: string;
  initialEditorText: string;
  showExample: boolean;
  errors: IJsonError[];
}

interface IProps {
  type: string;
  name: string;
  description: string;
  data: string;
  onConfirm(): void;
}

class CreateNewConfig2Modal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      initialEditorText: '',
      showExample: false,
      errors: [],
    };
  }

  componentDidMount() {
    this.discardChanges();
    // const editor: AE.AceEditor = this.refs.ace['editor'];
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
    const editorText = _.isEmpty(props.data) ? '{\n\t\n}' : props.data;
    this.setState({
      editorText: editorText,
    });
  }

  unsavedChanges() {
    return (
      this.state.editorText !==
      (_.isEmpty(this.props.data) ? '{\n\t\n}' : this.props.data)
    );
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
    // modalActionDispatchers.closeModal();
  };

  back = () => {
    modalActionDispatchers.showCreateNewConfigModal(
      this.props.type,
      this.props.name,
      this.props.description,
      this.state.editorText,
    );
  };

  exampleClick = () => {
    this.setState({
      showExample: !this.state.showExample,
    });
  };

  validateJson(): boolean {
    const errors = compileJsonSchema(DICTIONARY_SCHEMA, this.state.editorText);
    this.setState({
      errors,
    });
    return _.isEmpty(errors);
  }

  render() {
    const typeName = Parser.getPluginName(this.props.type, false);
    const test: AE.AceEditorProps = this.refs.ace;
    console.log(test);
    if (!_.isUndefined(test)) {
      console.log(test['editor']);
      console.log(test.editorProps);
      console.log(test);
    }
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
                onClick={() => this.validateJson()}>
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
        <button onClick={this.exampleClick} style={styles.collapsibleButton}>
          <div>{`${
            this.state.showExample ? 'Hide' : 'Show'
          } ${typeName.toLowerCase()} example data`}</div>
          <div style={styles.collapsibleRightSign}>
            {this.state.showExample ? '-' : '+'}
          </div>
        </button>
        {renderIf(this.state.showExample)(() => (
          <div style={styles.exampleData}>
            {getPostExample(this.props.type)}
          </div>
        ))}
        <AceEditor
          ref={'ace'}
          mode={'json'}
          height={'800px'}
          width={'100%'}
          name={'OutputJson'}
          theme={'monokai'}
          highlightActiveLine={true}
          annotations={
            _.isEmpty(this.state.errors)
              ? null
              : this.state.errors.map(err => {
                  return {
                    row: err.line,
                    column: 1,
                    type: 'error',
                    text: err.message,
                  };
                })
          }
          showGutter={true}
          showPrintMargin={false}
          focus={true}
          onChange={this.onChange}
          value={this.state.editorText}
          editorProps={{ $blockScrolling: true }}
        />
      </div>
    );
  }
}

const ComposedCreateNewConfig2Modal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('CreateNewConfig2Modal'),
)(CreateNewConfig2Modal);

export default ComposedCreateNewConfig2Modal;
