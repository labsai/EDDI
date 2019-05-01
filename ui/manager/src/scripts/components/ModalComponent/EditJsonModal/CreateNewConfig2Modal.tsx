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
import * as ace from 'brace';
import * as fs from 'fs';
import 'brace/mode/json';
import 'brace/theme/monokai';
import 'brace/snippets/json';

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
let staticWordCompleter = {
  getCompletions: function(editor, session, pos, prefix, callback) {
    const wordList = ['word', 'words', 'baz'];
    callback(
      null,
      wordList.map(function(word) {
        return {
          caption: word,
          value: word,
          meta: 'static',
        };
      }),
    );
  },
};

const snippet: string =
  '# AddPhrases\nsnippet phrases\n\t"phrases": [${1:}]\n# AddPhrase\nsnippet phrase\n\t{\n\t\t"phrase": "${1:phrase}",\n\t\t"exp": "${2:exp_name}(${3:exp_value})",\n\t\t"frequency": 0\n\t}\n# AddWords\nsnippet words\n\t"words": [${1:}]\n# AddWord\n\
snippet word\n\
	{\n\
		"word": "${1:word}",\n\
		"exp": "${2:exp_name}(${3:exp_value})",\n\
		"frequency": 0\n\
	}\n\
';

const snippet2: string = '# AddWords\n\
snippet words\n\
		"words": [${1:}]\n\
';

const snippet3: string =
  '# AddPhrase\n\
snippet phrase\n\
	{\n\
		"phrase": "${1:phrase}",\n\
		"exp": "${2:exp_name}(${3:exp_value})",\n\
		"frequency": 0\n\
	}\n\
';

const snippet4: string =
  '# AddPhrases\n\
snippet phrases\n\
		"phrases": [${1:}]\n\
';

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
    const langTools = ace.acequire('ace/ext/language_tools');
    // langTools.addCompleter(staticWordCompleter);
    const snippetManager = ace.acequire('ace/snippets').snippetManager;
    const editor: ace.Editor = ace.edit('OutputJson');
    console.log(snippet2.concat(snippet));
    const customSnippets = snippetManager.parseSnippetFile(snippet, 'json');
    const customSnippet2 = snippetManager.parseSnippetFile(snippet2, 'json');
    console.log(customSnippets);
    const editorSession = editor.getSession();
    const completer = editor['completers'][0];
    langTools.removeCompleters();
    langTools.addCompleter(completer);
    snippetManager.register(customSnippets, 'json');
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
          ref={'aceEditor'}
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
          setOptions={{ enableLiveAutocompletion: true, enableSnippets: true }}
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
