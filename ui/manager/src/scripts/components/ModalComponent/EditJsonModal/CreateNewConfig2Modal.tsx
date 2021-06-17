import { JSONSchema4 } from 'json-schema';
import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { compileJsonSchema, IJsonError } from '../../utils/helpers/JsonHelpers';
import Parser from '../../utils/Parser';
import useStyles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import useEditStyles from './EditJsonModal.styles';
import Editor from './Editor';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import JsonIsValid from './JsonIsValid';
import JsonSchemaForm from './JsonSchemaForm/JsonSchemaForm';
import clsx from 'clsx';

enum TabEnum {
  'editor',
  'form',
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

const CreateNewConfig2Modal = (props: IPrivateProps) => {
  // todo: reduxify this component and editor

  const [editorText, setEditorText] = React.useState('');
  const [isValidJson, setIsValidJson] = React.useState(false);
  const [errors, setErrors] = React.useState<IJsonError[]>([]);
  const [selectedTab, setSelectedTab] = React.useState<TabEnum>(TabEnum.editor);
  const classes = useStyles();
  const editClasses = useEditStyles();

  React.useEffect(() => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(props.type);
    discardChanges();
  }, []);

  React.useEffect(() => {
    discardChanges();
  }, [props.data, props.description, props.name, props.schema, props.type]);

  const onChange = (value) => {
    setEditorText(value);
    setIsValidJson(false);
  };

  const discardChanges = () => {
    const editorText = _.isEmpty(props.data) ? '{\n\t\n}' : props.data;
    setEditorText(editorText);
  };

  const unsavedChanges = () => {
    // todo: reduxify and refactor editor
    return false;
    /*return (
      editorText !==
      (_.isEmpty(props.data) ? '{\n\t\n}' : props.data)
    );*/
  };

  const isJsonString = () => {
    try {
      JSON.parse(editorText);
    } catch (e) {
      return false;
    }
    return true;
  };

  const createNew = () => {
    if (validateJson()) {
      eddiApiActionDispatchers.createNewConfigAction(
        props.type,
        props.name,
        props.description,
        editorText,
      );
      props.onConfirm ? props.onConfirm() : modalActionDispatchers.closeModal();
    }
  };

  const back = () => {
    modalActionDispatchers.showCreateNewConfigModal(
      props.type,
      props.name,
      props.description,
      editorText,
    );
  };

  const validateJson = () => {
    let errors: IJsonError[] = [];
    if (!isJsonString()) {
      const jsonParseError: IJsonError = {
        message: 'Error parsing JSON.',
        line: 0,
      };
      errors.push(jsonParseError);
    } else {
      errors = compileJsonSchema(props.schema, editorText);
    }
    const isValidJson = _.isEmpty(errors);
    setErrors(errors);
    setIsValidJson(isValidJson);
    return isValidJson;
  };

  const validateSchemaForm = () => {
    validateJson();
  };

  const typeName = Parser.getPluginName(props.type, false);
  return (
    <div>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          <div
            className={
              classes.botHeaderText
            }>{`Edit new ${typeName} JSON data`}</div>
          <div className={classes.modalTopHeaderCenter} />
          {unsavedChanges() && (
            <button
              className={classes.discardChanges}
              onClick={() => discardChanges()}>
              {'Discard changes'}
            </button>
          )}
          <WhiteButton
            onClick={() => back()}
            text={'Back'}
            classes={{ button: classes.backButton }}
          />
          <BlueButton
            onClick={createNew}
            text={`Create new ${typeName}`}
            disabled={!isJsonString()}
          />
        </div>
      </div>
      <div className={editClasses.tabs}>
        <div
          className={clsx(editClasses.tab, {
            [editClasses.tabDisabled]: selectedTab !== TabEnum.editor,
          })}
          onClick={() => setSelectedTab(TabEnum.editor)}>
          {'Editor'}
        </div>
        <div
          className={clsx(editClasses.tab, {
            [editClasses.tabDisabled]: selectedTab !== TabEnum.form,
          })}
          onClick={() =>
            validateJson() ? setSelectedTab(TabEnum.form) : validateJson()
          }>
          {'Form'}
        </div>
      </div>
      {isValidJson && <JsonIsValid />}
      {selectedTab === TabEnum.editor && (
        <div>
          {!_.isEmpty(errors) && <JsonErrors errors={errors} />}
          <JsonExample type={props.type} />
          <Editor
            type={props.type}
            data={editorText}
            errors={errors}
            onConfirm={createNew}
            onChange={onChange}
            validate={validateJson}
          />
        </div>
      )}
      {selectedTab === TabEnum.form && (
        <JsonSchemaForm
          schema={props.schema}
          data={editorText || '{}'}
          onChange={onChange}
          validate={validateSchemaForm}
        />
      )}
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
