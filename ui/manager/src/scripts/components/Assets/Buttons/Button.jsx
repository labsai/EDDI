'use strict';
var __assign =
  (this && this.__assign) ||
  Object.assign ||
  function(t) {
    for (var s, i = 1, n = arguments.length; i < n; i++) {
      s = arguments[i];
      for (var p in s)
        if (Object.prototype.hasOwnProperty.call(s, p)) t[p] = s[p];
    }
    return t;
  };
Object.defineProperty(exports, '__esModule', { value: true });
var React = require('react');
var recompose_1 = require('recompose');
var Radium = require('radium');
var styles = {
  button: {
    height: '35px',
    width: '108px',
    border: '1px solid #000',
    backgroundColor: '#CCC',
    color: '#000',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    textAlign: 'center',
  },
  disabled: {
    cursor: 'default',
  },
};
function getButtonStyling(props) {
  if (props.disabled) {
    return __assign(
      {},
      styles.button,
      styles.disabled,
      props.styles.button,
      props.customStyles,
      props.styles.disabled,
    );
  } else {
    return __assign(
      {},
      styles.button,
      props.styles.button,
      props.styles.active,
      props.customStyles,
    );
  }
}
var Button = function(props) {
  return (
    <button
      onClick={props.onClick}
      disabled={props.disabled}
      style={getButtonStyling(props)}>
      {props.text}
    </button>
  );
};
var ComposedButton = recompose_1.compose(
  recompose_1.pure,
  Radium,
  recompose_1.setDisplayName('Button'),
)(Button);
exports.default = ComposedButton;
