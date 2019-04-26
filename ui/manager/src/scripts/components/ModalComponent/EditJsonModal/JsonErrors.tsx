import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import {
  BLACK_COLOR,
  MEDIUM_FONT,
  MEDIUM_FONT2,
  RED_COLOR,
  SMALL_FONT,
  SMALL_FONT2,
} from '../../../../styles/DefaultStylingProperties';
import { CSSProperties } from 'react';
import * as Ajv from 'ajv';
import { IJsonError } from '../../utils/helpers/JsonHelpers';

const warningIcon = require('../../../../public/images/WarningIcon@3x.png');

const styles: CSSProperties = {
  content: {
    marginLeft: '20px',
  },
  header: {
    display: 'flex',
  },
  errorTitle: {
    color: RED_COLOR,
    fontSize: MEDIUM_FONT2,
  },
  errorContainer: {
    marginTop: '8px',
  },
  error: {
    display: 'flex',
    fontSize: MEDIUM_FONT,
  },
  key: {
    color: BLACK_COLOR,
    minWidth: '120px',
  },
  errorMessage: {
    color: RED_COLOR,
    fontWeight: 'bold',
  },
  errorSchemaPath: {
    color: RED_COLOR,
  },
  warningIcon: {
    height: '22px',
    marginRight: '5px',
  },
};

interface IProps {
  errors: IJsonError[];
}

class JsonErrors extends React.Component<IProps> {
  render() {
    return (
      <div style={styles.content}>
        <div style={styles.header}>
          <img src={warningIcon} style={styles.warningIcon} />
          <div style={styles.errorTitle}>{`Found ${
            this.props.errors.length
          } Error(s):`}</div>
        </div>
        <div>
          {this.props.errors.map((error, i) => (
            <div style={styles.errorContainer} key={i}>
              <div style={styles.error}>
                <div style={styles.key}>{'Location:'}</div>
                <div style={styles.errorMessage}>{`ERROR at line: ${error.line +
                  1}`}</div>
              </div>
              <div style={styles.error}>
                <div style={styles.key}>{'Message:'}</div>
                <div style={styles.errorSchemaPath}>{`${error.message}`}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedJsonErrors: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('JsonErrors'),
)(JsonErrors);

export default ComposedJsonErrors;
