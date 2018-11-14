import * as React from 'react';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import AceEditor from 'react-ace';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import BlueButton from '../Assets/Buttons/BlueButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import Parser from '../utils/Parser';
import { getPostExample } from '../utils/EddiConfigExampleData';
import * as _ from 'lodash';
import Radium = require('radium');

require('brace/mode/json');
require('brace/theme/monokai');

interface IState {
  editorText: string;
  initialEditorText: string;
  showExample: boolean;
}

interface IProps {
  type: string;
  name: string;
  description: string;
  data: string;
  onConfirm(): void;
}

class CreateNewConfig2Modal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      initialEditorText: '',
      showExample: false,
    };
  }

  componentDidMount() {
    this.discardChanges();
  }

  componentWillReceiveProps(nextProps) {
    this.discardChanges(nextProps);
  }

  onChange = value => {
    this.setState({
      editorText: value,
    });
  };

  discardChanges(props = this.props) {
    const editorText = _.isEmpty(props.data) ? '{\n\t\n}' : props.data;
    this.setState({
      editorText: editorText,
    });
  }

  unsavedChanges() {
    return (
      this.state.editorText !==
      (_.isEmpty(this.props.data) ? '{\n\t\n}' : this.props.data)
    );
  }

  isJsonString() {
    try {
      JSON.parse(this.state.editorText);
    } catch (e) {
      return false;
    }
    return true;
  }

  createNew = () => {
    eddiApiActionDispatchers.createNewConfigAction(
      this.props.type,
      this.props.name,
      this.props.description,
      this.state.editorText,
    );
    // modalActionDispatchers.closeModal();
    this.props.onConfirm();
  };

  back = () => {
    modalActionDispatchers.showCreateNewConfigModal(
      this.props.type,
      this.props.name,
      this.props.description,
      this.state.editorText,
    );
  };

  exampleClick = () => {
    this.setState({
      showExample: !this.state.showExample,
    });
  };

  render() {
    const typeName = Parser.getPluginName(this.props.type, false);
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            <div
              style={
                styles.botHeaderText
              }>{`Edit new ${typeName} JSON data`}</div>
            <div style={styles.modalTopHeaderCenter} />
            {renderIf(this.unsavedChanges())(() => (
              <button
                style={styles.discardChanges}
                onClick={() => this.discardChanges()}>
                {'Discard changes'}
              </button>
            ))}
            <WhiteButton
              onClick={() => this.back()}
              text={'Back'}
              customStyles={styles.backButton}
            />
            <BlueButton
              onClick={this.createNew}
              text={`Create new ${typeName}`}
              disabled={!this.isJsonString()}
            />
          </div>
        </div>
        <button onClick={this.exampleClick} style={styles.collapsibleButton}>
          <div>{`${
            this.state.showExample ? 'Hide' : 'Show'
          } ${typeName.toLowerCase()} example data`}</div>
          <div style={styles.collapsibleRightSign}>
            {this.state.showExample ? '-' : '+'}
          </div>
        </button>
        {renderIf(this.state.showExample)(() => (
          <div style={styles.exampleData}>
            {getPostExample(this.props.type)}
          </div>
        ))}
        <AceEditor
          mode={'json'}
          height={'800px'}
          width={'100%'}
          name={'OutputJson'}
          theme={'monokai'}
          highlightActiveLine={true}
          showGutter={true}
          showPrintMargin={false}
          focus={true}
          onChange={this.onChange}
          value={this.state.editorText}
          editorProps={{ $blockScrolling: true }}
        />
      </div>
    );
  }
}

const ComposedCreateNewConfig2Modal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('CreateNewConfig2Modal'),
)(CreateNewConfig2Modal);

export default ComposedCreateNewConfig2Modal;
