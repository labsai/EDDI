import * as metaSchema4 from 'ajv/lib/refs/json-schema-draft-04.json';
import { JSONSchema4 } from 'json-schema';
import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import Form from 'react-jsonschema-form';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { schemaSelector } from '../../../../selectors/SystemSelectors';
import Button from '../../../../components/Assets/Buttons/Button';
import {
  BLUE_COLOR,
  GREEN_COLOR,
  GREY_COLOR,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  form: {
    margin: '20px',
    color: WHITE_COLOR,

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
      // border: `1px dotted ${GREY_COLOR}`,
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

    '& input': {
      backgroundColor: GREY_COLOR,
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
});

interface IProps {
  schema?: JSONSchema4;
  data: string;
  onChange(data): void;
  validate(): void;
}

let yourForm;

const JsonSchemaForm: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  console.log('props.schema: ', props.schema);
  console.log('props.data: ', props.data);
  return (
    <div className={classes.form}>
      <Button
        text={'Validate form'}
        onClick={() => yourForm.submit()}
        classes={{ button: classes.validateButton }}
      />
      {!!props.schema && !!props.data && (
        <Form
          ref={(form) => {
            yourForm = form;
          }}
          additionalMetaSchemas={[metaSchema4]}
          schema={props.schema}
          formData={JSON.parse(props.data)}
          onChange={(data) =>
            props.onChange(JSON.stringify(data.formData, null, '\t'))
          }
          onSubmit={() => props.validate()}
          onError={() => console.log('errors')}>
          <br />
        </Form>
      )}
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
