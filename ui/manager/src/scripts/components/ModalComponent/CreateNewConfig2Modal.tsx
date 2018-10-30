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

require('brace/mode/json');
require('brace/theme/monokai');

interface IState {
  editorText: string;
  initialEditorText: string;
}

interface IProps {
  type: string;
  name: string;
  description: string;
  data: string;
}

class CreateNewConfig2Modal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      editorText: '',
      initialEditorText: '',
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
    const editorText = _.isEmpty(props.data)
      ? getPostExample(props.type)
      : props.data;
    this.setState({
      editorText: editorText,
    });
  }

  unsavedChanges() {
    return (
      this.state.editorText !==
      (_.isEmpty(this.props.data)
        ? getPostExample(this.props.type)
        : this.props.data)
    );
  }

  createNew = () => {
    eddiApiActionDispatchers.createNewConfigAction(
      this.props.type,
      this.props.name,
      this.props.description,
      this.state.editorText,
    );
    modalActionDispatchers.closeModal();
  };

  back = () => {
    modalActionDispatchers.showCreateNewConfigModal(
      this.props.type,
      this.props.name,
      this.props.description,
      this.state.editorText,
    );
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
            />
          </div>
        </div>
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
