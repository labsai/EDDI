'use strict';
Object.defineProperty(exports, '__esModule', { value: true });
var React = require('react');
var recompose_1 = require('recompose');
var Button_1 = require('./Button');
var Radium = require('radium');
var styles = {
  button: {
    border: '0px',
    color: '#FFFFFF',
    backgroundColor: '#0070D2',
  },
  disabled: {
    backgroundColor: '#c4c9d2',
    cursor: 'default',
  },
  active: {
    ':hover': {
      backgroundColor: '#4A90E2',
    },
    ':active': {
      backgroundColor: '#0070D2',
    },
  },
};
var BlueButton = function(props) {
  return (
    <Button_1.default
      text={props.text}
      onClick={props.onClick}
      disabled={props.disabled}
      styles={styles}
      customStyles={props.customStyles}
    />
  );
};
var ComposedBlueButton = recompose_1.compose(
  recompose_1.pure,
  Radium,
  recompose_1.setDisplayName('BlueButton'),
)(BlueButton);
exports.default = ComposedBlueButton;
