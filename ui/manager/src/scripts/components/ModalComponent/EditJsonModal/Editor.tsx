import * as React from 'react';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import AceEditor from 'react-ace';
import * as _ from 'lodash';
import { IJsonError } from '../../utils/helpers/JsonHelpers';
import 'ace-builds/src-noconflict/ext-language_tools';
import 'ace-builds/src-noconflict/mode-json';
import 'ace-builds/src-noconflict/theme-monokai';
import 'ace-builds/src-noconflict/snippets/json';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import { connect } from 'react-redux';
import { JSONSchema4 } from 'json-schema';
import useStyles from './Editor.styles';
import ExpandButton from './EditorButtons/ExpandButton';
import ValidateButton from './EditorButtons/ValidateButton';
import { Ace } from 'ace-builds';
import useGlobalStyle from '../../utils/useGlobalStyle';

interface IPublicProps {
  type: string;
  data: string;
  errors: IJsonError[];
  sliderRef?: React.MutableRefObject<any>;
  onConfirm(): void;
  onChange(value): void;
  validate(): void;
}

interface IPrivateProps extends IPublicProps {
  schema?: JSONSchema4;
}

const CreateNewConfig2Modal = ({
  type,
  data,
  errors,
  sliderRef,
  onChange,
  validate,
  schema,
}: IPrivateProps) => {
  const classes = useStyles();
  const [expanded, setExpanded] = React.useState<boolean>(false);
  const aceEditorRef = React.useRef(null);

  const handleOnChange = (value) => {
    onChange(value);
  };

  const handleUndo = (editor: Ace.Editor) => {
    editor.undo();
  };

  const css = `
    .slick-slider .slick-track, .slick-slider .slick-list {
      transform: none !important;
    }
  `;

  useGlobalStyle(expanded ? css : undefined);

  const handleExpand = () => {
    setExpanded(!expanded);
    if (expanded) {
      // need to fix full screen editor in slider
      sliderRef?.current?.slickPause?.();
    }
  };

  return (
    <div>
      <div className={expanded ? classes.expand : undefined}>
        <div className={classes.editorUI}>
          <div className={classes.editorButtons}>
            <ValidateButton onClick={() => validate()} />
            <ExpandButton
              classes={{ button: classes.expandButton }}
              expanded={expanded}
              onClick={handleExpand}
            />
          </div>
          <AceEditor
            ref={aceEditorRef}
            mode={'json'}
            height={expanded ? '100%' : '800px'}
            width={'100%'}
            name={'OutputJson'}
            theme={'monokai'}
            highlightActiveLine={true}
            annotations={
              _.isEmpty(errors)
                ? null
                : errors.map((err) => {
                    return {
                      row: err.line,
                      column: 1,
                      type: 'error',
                      text: err.message,
                    };
                  })
            }
            setOptions={{
              enableBasicAutocompletion: true,
              enableLiveAutocompletion: false,
              enableSnippets: true,
            }}
            showGutter={true}
            showPrintMargin={false}
            focus={true}
            onChange={handleOnChange}
            onLoad={(editor) => {
              editor.once('change', function () {
                editor.session.getUndoManager().reset();
              });
            }}
            value={data}
            editorProps={{ $blockScrolling: true }}
            commands={[
              {
                name: 'annotateCommand',
                bindKey: { win: 'ctrl-z', mac: 'Command-z' },
                exec: (editor) => handleUndo(editor),
              },
            ]}
          />
        </div>
      </div>
    </div>
  );
};

const ComposedCreateNewConfig2Modal: React.ComponentClass<IPrivateProps> =
  compose<IPrivateProps, IPrivateProps>(
    pure,
    connect(schemaSelector),
    setDisplayName('CreateNewConfig2Modal'),
  )(CreateNewConfig2Modal);

export default ComposedCreateNewConfig2Modal;
