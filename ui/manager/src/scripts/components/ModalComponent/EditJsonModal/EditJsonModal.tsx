import clsx from 'clsx';
import { JSONSchema4 } from 'json-schema';
import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { getTypeFromResource } from '../../utils/ApiFunctions';
import { compileJsonSchema, IJsonError } from '../../utils/helpers/JsonHelpers';
import useStyles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import useEditStyles from './EditJsonModal.styles';
import Editor from './Editor';
import JsonErrors from './JsonErrors';
import JsonExample from './JsonExample';
import JsonIsValid from './JsonIsValid';
import JsonSchemaForm from './JsonSchemaForm/JsonSchemaForm';

enum TabEnum {
  'editor',
  'form',
}

interface IPublicProps {
  resource: string;
  data: string;
  type: string;
}

interface IPrivateProps extends IPublicProps {
  schema?: JSONSchema4;
}

const EditJsonModal = (props: IPrivateProps) => {
  const isPackagePage = location.pathname.includes('packageview');
  const isBotPage = location.pathname.includes('botview');
  const urlSearchParams = new URLSearchParams(location.search);
  const botId = isBotPage
    ? location.pathname.split('/')?.[2]
    : urlSearchParams.get('botId');
  const packageId = isPackagePage
    ? location.pathname.split('/')?.[2]
    : urlSearchParams.get('packageId');
  // todo: reduxify this component and editor

  const [editorText, setEditorText] = React.useState('');
  const [errors, setErrors] = React.useState<IJsonError[]>([]);
  const [isValidJson, setIsValidJson] = React.useState(false);
  const [selectedTab, setSelectedTab] = React.useState<TabEnum>(TabEnum.editor);
  const classes = useStyles();
  const editClasses = useEditStyles();

  React.useEffect(() => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(
      getTypeFromResource(props.resource),
    );
    discardChanges();
  }, []);

  React.useEffect(() => {
    discardChanges();
  }, [props.resource, props.data, props.type, props.schema]);

  const onChange = (value) => {
    setEditorText(value);
    setIsValidJson(false);
  };

  const discardChanges = () => {
    setEditorText(props.data);
  };

  const unsavedChanges = () => {
    // todo: reduxify and refactor editor
    return false;
    /*
    return editorText !== props.data;
    */
  };

  const updateJson = (deploy: boolean = false) => {
    if (validateJson()) {
      const data = Object.assign(JSON.parse(editorText), {
        botId,
        packageId,
        deploy,
      });
      eddiApiActionDispatchers.updateJsonDataAction(props.resource, data);
      modalActionDispatchers.closeModal();
    }
  };

  const isJsonString = () => {
    try {
      JSON.parse(editorText);
    } catch (e) {
      return false;
    }
    return true;
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

  return (
    <div>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          <div className={classes.botHeaderText}>{'Edit existing data'}</div>
          <div className={classes.modalTopHeaderCenter} />
          {unsavedChanges() && (
            <button
              className={classes.discardChanges}
              onClick={() => validateJson()}>
              {'Discard changes'}
            </button>
          )}
          <BlueButton
            onClick={() => updateJson()}
            disabled={!unsavedChanges || !isJsonString()}
            text={'Save changes'}
          />
          <BlueButton
            onClick={() => updateJson(true)}
            disabled={!unsavedChanges || !isJsonString()}
            classes={{ button: classes.greenButton }}
            text={'Save & test'}
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
            onConfirm={updateJson}
            onChange={onChange}
            validate={validateJson}
          />
        </div>
      )}
      {selectedTab === TabEnum.form && (
        <JsonSchemaForm
          schema={props.schema}
          data={editorText}
          onChange={onChange}
          validate={validateSchemaForm}
        />
      )}
    </div>
  );
};

const ComposedEditJsonModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(schemaSelector),
  setDisplayName('EditJsonModal'),
)(EditJsonModal);

export default ComposedEditJsonModal;
