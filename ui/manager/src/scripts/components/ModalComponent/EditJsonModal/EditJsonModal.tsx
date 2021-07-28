import Modal from '@material-ui/core/Modal';
import clsx from 'clsx';
import { JSONSchema4 } from 'json-schema';
import * as _ from 'lodash';
import * as React from 'react';
import { connect, useDispatch } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { editPluginDataAction } from '../../../actions/EddiApiActions';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { schemaSelector } from '../../../selectors/SystemSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { getTypeFromResource } from '../../utils/ApiFunctions';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import getIdsFromPath from '../../utils/helpers/getIdsFromPath';
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
  descriptor?: IDetailedDescriptor;
  resource: string;
  data: string;
  type: string;
  showViewJson?: () => void;
}

interface IPrivateProps extends IPublicProps {
  schema?: JSONSchema4;
}

const EditJsonModal = (props: IPrivateProps) => {
  const dispatch = useDispatch();

  const { botId, packageId } = getIdsFromPath();
  // todo: reduxify this component and editor

  const [editorText, setEditorText] = React.useState('');
  const [errors, setErrors] = React.useState<IJsonError[]>([]);
  const [isValidJson, setIsValidJson] = React.useState(false);
  const [discardOpened, setDiscardOpened] = React.useState(false);
  const [selectedTab, setSelectedTab] = React.useState<TabEnum>(TabEnum.form);
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
    dispatch(
      editPluginDataAction(
        props.descriptor.id,
        value,
        props.resource,
        props.schema,
      ),
    );
    setIsValidJson(false);
  };

  const discardChanges = () => {
    setEditorText(props.data);
  };

  const handleDiscardChanges = () => {
    setDiscardOpened(true);
  };

  const handleClose = () => {
    setDiscardOpened(false);
  };

  const unsavedChanges = () => editorText !== props.data;

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
          {!props.descriptor ? (
            <div className={classes.botHeaderText}>{'Edit existing data'}</div>
          ) : (
            <div
              className={clsx(
                classes.botHeaderText,
                classes.botHeaderTextSmall,
              )}>{`Edit existing data of '${
              props.descriptor.name || props.descriptor.id
            }'`}</div>
          )}
          <div className={classes.modalTopHeaderCenter} />
          {props.showViewJson && (
            <BlueButton
              classes={{ button: classes.showViewJson }}
              onClick={() => props.showViewJson()}
              text={'View'}
            />
          )}
          {unsavedChanges() && (
            <BlueButton
              classes={{ button: classes.discardChanges }}
              onClick={handleDiscardChanges}
              text={'Discard changes'}
            />
          )}
        </div>
      </div>
      <div className={editClasses.tabs}>
        <div
          className={clsx(editClasses.tab, {
            [editClasses.tabDisabled]: selectedTab !== TabEnum.form,
          })}
          onClick={() =>
            validateJson() ? setSelectedTab(TabEnum.form) : validateJson()
          }>
          {'Form'}
        </div>
        <div
          className={clsx(editClasses.tab, {
            [editClasses.tabDisabled]: selectedTab !== TabEnum.editor,
          })}
          onClick={() => setSelectedTab(TabEnum.editor)}>
          {'JSON Editor'}
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
      <Modal open={discardOpened} onClose={handleClose}>
        <div className={classes.paper}>
          <p>Are you sure?</p>
          <div>
            <BlueButton
              classes={{ button: classes.showViewJson }}
              onClick={() => {
                discardChanges();
                handleClose();
              }}
              text={'Discard'}
            />
            <BlueButton
              classes={{ button: classes.discardChanges }}
              onClick={handleClose}
              text={'Cancel'}
            />
          </div>
        </div>
      </Modal>
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
