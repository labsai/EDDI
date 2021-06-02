import * as React from 'react';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import AceEditor from 'react-ace';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import * as _ from 'lodash';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import styles from './Editor.styles';
import {
  compileJsonSchema,
  getSnippets,
  IJsonError,
} from '../../utils/helpers/JsonHelpers';
import 'ace-builds/src-noconflict/ext-language_tools';
import 'ace-builds/src-noconflict/mode-json';
import 'ace-builds/src-noconflict/theme-monokai';
import 'ace-builds/src-noconflict/snippets/json';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { connect } from 'react-redux';
import { JSONSchema4 } from 'json-schema';
import CheckBox from './EditorButtons/CheckBox';
import ExpandButton from './EditorButtons/ExpandButton';
import UndoButton from './EditorButtons/UndoButton';
import RedoButton from './EditorButtons/RedoButton';
import ValidateButton from './EditorButtons/ValidateButton';

interface IState {
  editorText: string;
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
      editorText: '{}',
      expanded: false,
      enableBasicAutocompletion: true,
      enableLiveAutocompletion: false,
    };
  }

  componentDidMount() {
    this.discardChanges();
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.discardChanges();
    }
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

const ComposedCreateNewConfig2Modal: React.ComponentClass<IPrivateProps> = compose<
  IPrivateProps, IPrivateProps
>(pure, connect(schemaSelector), setDisplayName('CreateNewConfig2Modal'))(
  CreateNewConfig2Modal,
);

export default ComposedCreateNewConfig2Modal;
