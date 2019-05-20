import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import AceEditor from 'react-ace';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
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

const langTools = ace.acequire('ace/ext/language_tools');
const snippetManager = ace.acequire('ace/snippets').snippetManager;

interface IState {
  editorText: string;
  showExample: boolean;
  errors: IJsonError[];
}

interface IPublicProps {
  type: string;
  data: string;
  onConfirm(): void;
  onChange(value): void;
}

interface IPrivateProps extends IPublicProps {
  schema?: object;
}

class CreateNewConfig2Modal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      showExample: false,
      errors: [],
    };
  }

  componentDidMount() {
    eddiApiActionDispatchers.fetchJsonSchemaAction(this.props.type);
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

  onConfirm = () => {
    if (this.validateJson()) {
      this.props.onConfirm();
    }
  };

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
        {renderIf(!_.isEmpty(this.state.errors))(() => (
          <JsonErrors errors={this.state.errors} />
        ))}
        <JsonExample type={this.props.type} />
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

const ComposedCreateNewConfig2Modal: Component<IPrivateProps> = compose<
  IPrivateProps
>(pure, connect(schemaSelector), setDisplayName('CreateNewConfig2Modal'))(
  CreateNewConfig2Modal,
);

export default ComposedCreateNewConfig2Modal;
