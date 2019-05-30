import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import AceEditor from 'react-ace';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import styles from './Editor.styles';
import {
  compileJsonSchema,
  getSnippets,
  IJsonError,
} from '../../utils/helpers/JsonHelpers';
import * as ace from 'brace';
import 'brace/ext/language_tools';
import 'brace/mode/json';
import 'brace/theme/monokai';
import 'brace/snippets/json';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { connect } from 'react-redux';
import { JSONSchema4 } from 'json-schema';
import CheckBox from './EditorButtons/CheckBox';
import ExpandButton from './EditorButtons/ExpandButton';
import UndoButton from './EditorButtons/UndoButton';
import RedoButton from './EditorButtons/RedoButton';
import ValidateButton from './EditorButtons/ValidateButton';

const langTools = ace.acequire('ace/ext/language_tools');
const snippetManager = ace.acequire('ace/snippets').snippetManager;

interface IState {
  editorText: string;
  editor: ace.Editor;
  expanded: boolean;
  enableBasicAutocompletion: boolean;
  enableLiveAutocompletion: boolean;
}

interface IPublicProps {
  type: string;
  data: string;
  errors: IJsonError[];
  onConfirm(): void;
  onChange(value): void;
  validate(): void;
}

interface IPrivateProps extends IPublicProps {
  schema?: JSONSchema4;
}

class CreateNewConfig2Modal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      editor: null,
      expanded: false,
      enableBasicAutocompletion: true,
      enableLiveAutocompletion: false,
    };
  }

  componentDidMount() {
    this.setState({ editor: ace.edit('OutputJson') });
    this.discardChanges();
    this.initEditor();
  }

  componentWillUnmount() {
    snippetManager.snippetMap.json = [];
  }

  initEditor() {
    const customSnippets = getSnippets(this.props.type);
    snippetManager.register(customSnippets, 'json');
    langTools.setCompleters([langTools.snippetCompleter]);
  }

  componentWillReceiveProps(nextProps) {
    this.discardChanges(nextProps);
  }

  onChange = value => {
    this.props.onChange(value);
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

  render() {
    return (
      <div>
        <div style={this.state.expanded ? styles.expand : {}}>
          <div style={styles.editorUI}>
            <div style={styles.editorButtons}>
              <ValidateButton onClick={() => this.props.validate()} />
              <UndoButton onClick={() => this.state.editor.undo()} />
              <RedoButton onClick={() => this.state.editor.redo()} />
              <CheckBox
                checked={this.state.enableBasicAutocompletion}
                onClick={() =>
                  this.setState({
                    enableBasicAutocompletion: !this.state
                      .enableBasicAutocompletion,
                  })
                }
              />
              <div style={styles.checkBoxText}>
                {'Enable Basic Autocomplete'}
              </div>
              <CheckBox
                checked={this.state.enableLiveAutocompletion}
                onClick={() =>
                  this.setState({
                    enableLiveAutocompletion: !this.state
                      .enableLiveAutocompletion,
                  })
                }
              />
              <div style={styles.checkBoxText}>
                {'Enable Live Autocomplete'}
              </div>
              <ExpandButton
                customStyles={styles.expandButton}
                expanded={this.state.expanded}
                onClick={() =>
                  this.setState({ expanded: !this.state.expanded })
                }
              />
            </div>
            <AceEditor
              ref={'aceEditor'}
              mode={'json'}
              height={this.state.expanded ? '100%' : '800px'}
              width={'100%'}
              name={'OutputJson'}
              theme={'monokai'}
              highlightActiveLine={true}
              annotations={
                _.isEmpty(this.props.errors)
                  ? null
                  : this.props.errors.map(err => {
                      return {
                        row: err.line,
                        column: 1,
                        type: 'error',
                        text: err.message,
                      };
                    })
              }
              setOptions={{
                enableBasicAutocompletion: this.state.enableBasicAutocompletion,
                enableLiveAutocompletion: this.state.enableLiveAutocompletion,
                enableSnippets: true,
              }}
              showGutter={true}
              showPrintMargin={false}
              focus={true}
              onChange={this.onChange}
              value={this.state.editorText}
              editorProps={{ $blockScrolling: true }}
            />
          </div>
        </div>
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
