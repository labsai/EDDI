'use strict';
var __extends =
  (this && this.__extends) ||
  (function () {
    var extendStatics =
      Object.setPrototypeOf ||
      ({ __proto__: [] } instanceof Array &&
        function (d, b) {
          d.__proto__ = b;
        }) ||
      function (d, b) {
        for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
      };
    return function (d, b) {
      extendStatics(d, b);
      function __() {
        this.constructor = d;
      }
      d.prototype =
        b === null
          ? Object.create(b)
          : ((__.prototype = b.prototype), new __());
    };
  })();
Object.defineProperty(exports, '__esModule', { value: true });
var React = require('react');
var recompose_1 = require('recompose');
var DefaultStylingProperties_1 = require('../../../../styles/DefaultStylingProperties');
var warningIcon = require('../../../../public/images/WarningIcon@3x.png');
var styles = {
  content: {
    marginLeft: '20px',
  },
  header: {
    display: 'flex',
  },
  errorTitle: {
    color: DefaultStylingProperties_1.RED_COLOR,
    fontSize: DefaultStylingProperties_1.MEDIUM_FONT2,
  },
  errorContainer: {
    marginTop: '8px',
  },
  error: {
    display: 'flex',
    fontSize: DefaultStylingProperties_1.MEDIUM_FONT,
  },
  key: {
    color: DefaultStylingProperties_1.BLACK_COLOR,
    minWidth: '120px',
  },
  errorMessage: {
    color: DefaultStylingProperties_1.RED_COLOR,
    fontWeight: 'bold',
  },
  errorSchemaPath: {
    color: DefaultStylingProperties_1.RED_COLOR,
  },
  warningIcon: {
    height: '22px',
    marginRight: '5px',
  },
};
var JsonErrors = (function (_super) {
  __extends(JsonErrors, _super);
  function JsonErrors() {
    return (_super !== null && _super.apply(this, arguments)) || this;
  }
  JsonErrors.prototype.render = function () {
    return (
      <div style={styles.content}>
        <div style={styles.header}>
          <img src={warningIcon} style={styles.warningIcon} />
          <div style={styles.errorTitle}>
            {'Found ' + this.props.errors.length + ' Error(s):'}
          </div>
        </div>
        <div>
          {this.props.errors.map(function (error, i) {
            return (
              <div style={styles.errorContainer} key={i}>
                <div style={styles.error}>
                  <div style={styles.key}>{'Location:'}</div>
                  <div style={styles.errorMessage}>
                    {'ERROR at line: ' + (error.line + 1)}
                  </div>
                </div>
                <div style={styles.error}>
                  <div style={styles.key}>{'Message:'}</div>
                  <div style={styles.errorSchemaPath}>{'' + error.message}</div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    );
  };
  return JsonErrors;
})(React.Component);
var ComposedJsonErrors = recompose_1.compose(
  recompose_1.pure,
  recompose_1.setDisplayName('JsonErrors'),
)(JsonErrors);
exports.default = ComposedJsonErrors;
