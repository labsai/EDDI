import * as metaSchema4 from 'ajv/lib/refs/json-schema-draft-04.json';
import { JSONSchema4 } from 'json-schema';
import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import Form from 'react-jsonschema-form';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { schemaSelector } from '../../../../selectors/SystemSelectors';
import Button from '../../../../components/Assets/Buttons/Button';

const useStyles = makeStyles({
  form: {
    margin: '20px',
  },
  validateButton: {
    backgroundColor: '#4BCA81',
    color: '#fff',
    border: 'none',
    textTransform: 'none',
    padding: '6px 12px',
    marginBottom: '10px',

    '& .MuiButton-label': {
      fontSize: '1rem',
    },
    '&:hover': {
      backgroundColor: '#4BCA81',
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
