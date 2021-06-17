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
var ModalComponent_styles_1 = require('../ModalComponent.styles');
require('../ModalComponent.styles.scss');
var recompose_1 = require('recompose');
var react_ace_1 = require('react-ace');
var EddiApiActionDispatchers_1 = require('../../../actions/EddiApiActionDispatchers');
var BlueButton_1 = require('../../Assets/Buttons/BlueButton');
var WhiteButton_1 = require('../../Assets/Buttons/WhiteButton');
var ModalActionDispatchers_1 = require('../../../actions/ModalActionDispatchers');
var Parser_1 = require('../../utils/Parser');
var EddiConfigExampleData_1 = require('../../utils/EddiConfigExampleData');
var _ = require('lodash');
var JsonErrors_1 = require('./JsonErrors');
var JsonSchemas_1 = require('../../utils/JsonSchemas/JsonSchemas');
var JsonHelpers_1 = require('../../utils/helpers/JsonHelpers');
require('brace/mode/json');
require('brace/theme/monokai');
var CreateNewConfig2Modal = (function (_super) {
  __extends(CreateNewConfig2Modal, _super);
  function CreateNewConfig2Modal(props) {
    var _this = _super.call(this, props) || this;
    _this.onChange = function (value) {
      _this.setState({
        editorText: value,
      });
    };
    _this.createNew = function () {
      if (_this.validateJson()) {
        EddiApiActionDispatchers_1.default.createNewConfigAction(
          _this.props.type,
          _this.props.name,
          _this.props.description,
          _this.state.editorText,
        );
        _this.props.onConfirm();
      }
      // modalActionDispatchers.closeModal();
    };
    _this.back = function () {
      ModalActionDispatchers_1.default.showCreateNewConfigModal(
        _this.props.type,
        _this.props.name,
        _this.props.description,
        _this.state.editorText,
      );
    };
    _this.exampleClick = function () {
      _this.setState({
        showExample: !_this.state.showExample,
      });
    };
    _this.state = {
      editorText: '',
      initialEditorText: '',
      showExample: false,
      errors: [],
    };
    return _this;
  }
  CreateNewConfig2Modal.prototype.componentDidMount = function () {
    this.discardChanges();
    // const editor: AE.AceEditor = this.refs.ace['editor'];
  };
  CreateNewConfig2Modal.prototype.componentWillReceiveProps = function (
    nextProps,
  ) {
    this.discardChanges(nextProps);
  };
  CreateNewConfig2Modal.prototype.discardChanges = function (props) {
    if (props === void 0) {
      props = this.props;
    }
    var editorText = _.isEmpty(props.data) ? '{\n\t\n}' : props.data;
    this.setState({
      editorText: editorText,
    });
  };
  CreateNewConfig2Modal.prototype.unsavedChanges = function () {
    return (
      this.state.editorText !==
      (_.isEmpty(this.props.data) ? '{\n\t\n}' : this.props.data)
    );
  };
  CreateNewConfig2Modal.prototype.isJsonString = function () {
    try {
      JSON.parse(this.state.editorText);
    } catch (e) {
      return false;
    }
    return true;
  };
  CreateNewConfig2Modal.prototype.validateJson = function () {
    var errors = JsonHelpers_1.compileJsonSchema(
      JsonSchemas_1.DICTIONARY_SCHEMA,
      this.state.editorText,
    );
    this.setState({
      errors: errors,
    });
    return _.isEmpty(errors);
  };
  CreateNewConfig2Modal.prototype.render = function () {
    var _this = this;
    var typeName = Parser_1.default.getPluginName(this.props.type, false);
    var test = this.refs.ace;
    console.log(test);
    if (!_.isUndefined(test)) {
      console.log(test['editor']);
      console.log(test.editorProps);
      console.log(test);
    }
    return (
      <div>
        <div style={ModalComponent_styles_1.default.modalHeader}>
          <div style={ModalComponent_styles_1.default.modalTopHeader}>
            <div style={ModalComponent_styles_1.default.botHeaderText}>
              {'Edit new ' + typeName + ' JSON data'}
            </div>
            <div style={ModalComponent_styles_1.default.modalTopHeaderCenter} />
            {this.unsavedChanges() && (
              <button
                style={ModalComponent_styles_1.default.discardChanges}
                onClick={function () {
                  return _this.validateJson();
                }}>
                {'Discard changes'}
              </button>
            )}
            <WhiteButton_1.default
              onClick={function () {
                return _this.back();
              }}
              text={'Back'}
              customStyles={ModalComponent_styles_1.default.backButton}
            />
            <BlueButton_1.default
              onClick={this.createNew}
              text={'Create new ' + typeName}
              disabled={!this.isJsonString()}
            />
          </div>
        </div>
        {!_.isEmpty(this.state.errors) && (
          <JsonErrors_1.default errors={_this.state.errors} />
        )}
        <button
          onClick={this.exampleClick}
          style={ModalComponent_styles_1.default.collapsibleButton}>
          <div>
            {(this.state.showExample ? 'Hide' : 'Show') +
              ' ' +
              typeName.toLowerCase() +
              ' example data'}
          </div>
          <div style={ModalComponent_styles_1.default.collapsibleRightSign}>
            {this.state.showExample ? '-' : '+'}
          </div>
        </button>
        {!!this.state.showExample && (
          <div style={ModalComponent_styles_1.default.exampleData}>
            {EddiConfigExampleData_1.getPostExample(_this.props.type)}
          </div>
        )}
        <react_ace_1.default
          ref={'ace'}
          mode={'json'}
          height={'800px'}
          width={'100%'}
          name={'OutputJson'}
          theme={'monokai'}
          highlightActiveLine={true}
          annotations={
            _.isEmpty(this.state.errors)
              ? null
              : this.state.errors.map(function (err) {
                  return {
                    row: err.line,
                    column: 1,
                    type: 'error',
                    text: err.message,
                  };
                })
          }
          showGutter={true}
          showPrintMargin={false}
          focus={true}
          onChange={this.onChange}
          value={this.state.editorText}
          editorProps={{ $blockScrolling: true }}
        />
      </div>
    );
  };
  return CreateNewConfig2Modal;
})(React.Component);
var ComposedCreateNewConfig2Modal = recompose_1.compose(
  recompose_1.pure,
  recompose_1.setDisplayName('CreateNewConfig2Modal'),
)(CreateNewConfig2Modal);
exports.default = ComposedCreateNewConfig2Modal;
