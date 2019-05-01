'use strict';
var __extends =
  (this && this.__extends) ||
  (function() {
    var extendStatics =
      Object.setPrototypeOf ||
      ({ __proto__: [] } instanceof Array &&
        function(d, b) {
          d.__proto__ = b;
        }) ||
      function(d, b) {
        for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
      };
    return function(d, b) {
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
var ModalComponent_styles_1 = require('../ModalComponent.styles');
require('../ModalComponent.styles.scss');
var recompose_1 = require('recompose');
var ModalActionDispatchers_1 = require('../../../actions/ModalActionDispatchers');
var Parser_1 = require('../../utils/Parser');
var customStyles = {
  createNewBotButton: {
    backgroundColor: '#0070D2',
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    cursor: 'pointer',
    fontSize: '12px',
    height: '36px',
    marginLeft: '60%',
    marginTop: '8px',
    textAlign: 'center',
    minWidth: '100px',
  },
};
var CreateNewConfigModal = (function(_super) {
  __extends(CreateNewConfigModal, _super);
  function CreateNewConfigModal(props) {
    var _this = _super.call(this, props) || this;
    _this.nextButton = function() {
      ModalActionDispatchers_1.default.showCreateNewConfig2Modal(
        _this.props.type,
        _this.state.name,
        _this.state.description,
        _this.props.data,
        _this.props.onConfirm,
      );
    };
    _this.state = {
      name: props.name || '',
      description: props.description || '',
    };
    return _this;
  }
  CreateNewConfigModal.prototype.getButtonStyle = function() {
    if (!this.state.name) {
      return __assign({}, customStyles.createNewBotButton, {
        backgroundColor: '#c4c9d2',
      });
    } else {
      return __assign({}, customStyles.createNewBotButton, {
        backgroundColor: '#0070D2',
        cursor: 'pointer',
      });
    }
  };
  CreateNewConfigModal.prototype.render = function() {
    var _this = this;
    var typeName = Parser_1.default.getPluginName(this.props.type, false);
    return (
      <div>
        <div style={ModalComponent_styles_1.default.tallModalHeader}>
          <div style={ModalComponent_styles_1.default.modalTopHeader}>
            <h2 style={ModalComponent_styles_1.default.createPackageHeaderText}>
              {'Create new ' + typeName}
            </h2>
            <div style={ModalComponent_styles_1.default.modalTopHeaderCenter} />
            <button
              disabled={!this.state.name}
              onClick={function() {
                _this.nextButton();
              }}
              style={this.getButtonStyle()}>
              {'Next'}
            </button>
          </div>
        </div>
        <div style={ModalComponent_styles_1.default.content}>
          <div style={ModalComponent_styles_1.default.botText}>
            {'Give the ' + typeName + ' a name'}
            <div style={ModalComponent_styles_1.default.inputBoxContent}>
              <textarea
                defaultValue={this.state.name}
                name={'name'}
                style={ModalComponent_styles_1.default.inputBoxName}
                placeholder={'Give the ' + typeName + ' a name..'}
                onChange={function(e) {
                  return _this.setState({
                    name: e.target.value,
                  });
                }}
              />
            </div>
          </div>
          <div style={ModalComponent_styles_1.default.botText}>
            {'Give the ' + typeName + ' a short description'}
            <div style={ModalComponent_styles_1.default.inputBoxContent}>
              <textarea
                defaultValue={this.state.description}
                name={'description'}
                style={ModalComponent_styles_1.default.inputBox}
                placeholder={'Give the ' + typeName + ' a short description..'}
                onChange={function(e) {
                  return _this.setState({
                    description: e.target.value,
                  });
                }}
              />
            </div>
          </div>
        </div>
      </div>
    );
  };
  return CreateNewConfigModal;
})(React.Component);
var ComposedCreateNewConfigModal = recompose_1.compose(
  recompose_1.pure,
  recompose_1.setDisplayName('CreateNewConfigModal'),
)(CreateNewConfigModal);
exports.default = ComposedCreateNewConfigModal;
