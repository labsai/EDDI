import { makeStyles } from '@material-ui/core/styles';
import * as metaSchema4 from 'ajv/lib/refs/json-schema-draft-04.json';
import { JSONSchema4 } from 'json-schema';
import * as React from 'react';
import Form from 'react-jsonschema-form';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import * as uuid from 'uuid';
import {
  BLUE_COLOR,
  DARK_GREY_COLOR,
  GREEN_COLOR,
  GREY_COLOR,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../../../styles/DefaultStylingProperties';
import Button from '../../../../components/Assets/Buttons/Button';
import { schemaSelector } from '../../../../selectors/SystemSelectors';
import RecursiveTreeView from './RecursiveTreeView';
import clsx from 'clsx';

const notVerticalSpacing = {
  marginBottom: 0,
  marginTop: 0,
  paddingBottom: 0,
  paddingTop: 0,
};

const useStyles = makeStyles({
  formContainer: {
    display: 'flex',
    flexDirection: 'row',
  },
  formTree: {
    flex: 1,
    paddingLeft: '10px',
    paddingTop: '15px',
    maxHeight: '70vh',
    overflow: 'auto',
  },
  form: {
    flex: 4,
    margin: '20px',
    color: WHITE_COLOR,
    maxHeight: '70vh',
    overflow: 'auto',

    '& legend': {
      color: WHITE_COLOR,
      fontSize: '14px',
      fontWeight: 'bold',
      padding: '10px 5px',
    },

    '& fieldset': {
      flexDirection: 'column!important',
    },
    '& label': {
      color: WHITE_COLOR,
      fontSize: '14px',
      fontWeight: 'bold',
    },

    '& .form-group': {
      padding: '5px',
      borderBottom: `1px dashed ${GREY_COLOR}`,
      borderLeft: `1px dashed ${GREY_COLOR}`,
    },

    '& .field-array > div': {
      paddingLeft: '10px',
    },

    '& #root_outputSet__title': {
      color: WHITE_COLOR,
      fontSize: '18px',
    },

    '& input, select': {
      backgroundColor: GREY_COLOR,
      color: WHITE_COLOR,
    },
    '& .panel-default': {
      backgroundColor: DARK_GREY_COLOR,
      color: WHITE_COLOR,
    },

    '& .col-xs-9': {
      width: '85%',
    },
    '& .col-xs-offset-9': {
      marginLeft: '85%',
    },
    '& .col-xs-3': {
      width: '10%',
      padding: 0,
    },

    '& .btn-danger': {
      backgroundColor: RED_COLOR,
      color: WHITE_COLOR,
      border: 'none',
      transition: 'border 0s ease, padding 0s ease',
      padding: '6px 6px',

      '&:hover': {
        backgroundColor: 'transparent',
        color: RED_COLOR,
        border: `2px solid ${RED_COLOR}!important`,
        padding: '4px 4px!important',
        transition: 'border 0s ease, padding 0s ease',
      },
    },
    '& .btn-info': {
      backgroundColor: BLUE_COLOR,
      color: WHITE_COLOR,
      border: 'none',
      transition: 'border 0s ease, padding 0s ease',

      '&:hover': {
        backgroundColor: 'transparent',
        color: BLUE_COLOR,
        border: `2px solid ${BLUE_COLOR}!important`,
        padding: '4px 11px',
        transition: 'border 0s ease, padding 0s ease',
      },
    },
  },
  validateButton: {
    backgroundColor: GREEN_COLOR,
    textTransform: 'none',
    padding: '6px 12px',
    marginBottom: '10px',
    color: WHITE_COLOR,
    border: 'none',
    transition: 'border 0s ease, padding 0s ease',

    '& .MuiButton-label': {
      fontSize: '1rem',
    },
    '&:hover': {
      backgroundColor: 'transparent',
      color: GREEN_COLOR,
      border: `2px solid ${GREEN_COLOR}!important`,
      padding: '4px 11px',
      transition: 'border 0s ease, padding 0s ease',
    },
  },
  readOnly: {
    '& button': {
      display: 'none',
      pointerEvents: 'none',
    },
    '& input, & select': {
      '&:disabled': {
        backgroundColor: DARK_GREY_COLOR,
        opacity: 0.6,
        color: WHITE_COLOR,
      },
      backgroundColor: DARK_GREY_COLOR,
      opacity: 0.6,
      color: WHITE_COLOR,
      pointerEvents: 'none',
    },
    '& *': {
      ...notVerticalSpacing,
    },
  },
  jsonForm: {},
});

interface IProps {
  schema?: JSONSchema4;
  data: string;
  readOnly?: boolean;
  onChange(data): void;
  validate(): void;
}

const JsonSchemaForm: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  const readOnly = props.readOnly;
  const formRef = React.useRef(null);

  const disableInputs = (disabled = true) => {
    const inputs = document.querySelectorAll('.json-form input');
    const selects = document.querySelectorAll('.json-form select');
    if (inputs) {
      const inputsArray = Array.from(inputs);
      inputsArray?.forEach?.((e: HTMLInputElement) => {
        e.disabled = disabled;
      });
    }
    if (selects) {
      const selectsArray = Array.from(selects);
      selectsArray?.forEach?.((e: HTMLSelectElement) => {
        e.disabled = disabled;
      });
    }
  };

  React.useEffect(() => {
    if (!readOnly) {
      return;
    }
    setTimeout(() => {
      disableInputs();
    }, 800);

    return () => disableInputs(false);
  }, []);

  if (!props.data) {
    return null;
  }
  const data = JSON.parse(props.data);

  const handleSubmit = () => {
    props.validate();
  };

  return (
    <div
      className={clsx(
        classes.formContainer,
        readOnly ? classes.readOnly : null,
      )}>
      <div className={classes.formTree}>
        <RecursiveTreeView data={data} />
      </div>
      <div className={clsx(classes.form, 'json-form')}>
        <Button
          text={'Validate form'}
          onClick={handleSubmit}
          classes={{ button: classes.validateButton }}
        />
        {!!props.schema && !!props.data && (
          <Form
            ref={formRef}
            className={readOnly ? classes.readOnly : null}
            additionalMetaSchemas={[metaSchema4]}
            schema={props.schema}
            formData={data}
            onChange={(data) =>
              props.onChange(JSON.stringify(data.formData, null, '\t'))
            }
            onSubmit={() => props.validate()}
            liveValidate
            onError={() => console.log('errors')}>
            <br />
          </Form>
        )}
      </div>
    </div>
  );
};

const ComposedJsonSchemaForm: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  connect(schemaSelector),
  setDisplayName('JsonSchemaForm'),
)(JsonSchemaForm);

export default ComposedJsonSchemaForm;
