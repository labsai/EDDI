'use strict';
Object.defineProperty(exports, '__esModule', { value: true });
var React = require('react');
var recompose_1 = require('recompose');
var Button_1 = require('./Button');
var Radium = require('radium');
var styles = {
  button: {
    border: '1px solid #D8DDE6',
    backgroundColor: '#FFFFFF',
    color: '#0070D2',
  },
  disabled: {
    color: '#D8DDE6',
    cursor: 'default',
  },
  active: {
    ':hover': {
      backgroundColor: '#F4F6F9',
    },
    ':active': {
      backgroundColor: '#FFFFFF',
    },
  },
};
var WhiteButton = function (props) {
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
var ComposedWhiteButton = recompose_1.compose(
  recompose_1.pure,
  Radium,
  recompose_1.setDisplayName('WhiteButton'),
)(WhiteButton);
exports.default = ComposedWhiteButton;
