import * as React from 'react';
import * as Modal from 'react-modal';
import { connect } from 'react-redux';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { modalSelector } from '../../selectors/ModalSelectors';
import {
  IBot,
  IDescriptor,
  IDetailedDescriptor,
  IPackage,
} from '../utils/AxiosFunctions';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Link, browserHistory } from 'react-router-dom';
import { Component, compose, pure, setDisplayName } from 'recompose';
import CreateBotModal from './CreateBotModal';
import EditBotModal from './EditBotModal';
import AddPackagesModal from './AddPackagesModal/AddPackagesModal';
import AddPluginModal from './AddPluginModal/AddPluginModal';
import CreatePackageModal from './CreatePackageModal';
import EditPackageModal from './EditPackageModal';
import UpdatePackageModal from './UpdatePackageModal';
import { ModalEnum } from '../utils/ModalEnum';
import EditJsonModal from './EditJsonModal';
import ViewJsonModal from './ViewJsonModal/ViewJsonModal';
import CreateNewConfigModal from './CreateNewConfigModal';
import CreateNewConfig2Modal from './CreateNewConfig2Modal';
import UpdatePackagesModal from './UpdateConfigsModal/UpdatePackagesModal';
import UpdateBotsModal from './UpdateConfigsModal/UpdateBotsModal';
import ConfirmModal from './ConfirmModal';

const customStyles = {
  content: {
    background: '#fff',
    border: '1px solid #ccc',
    borderRadius: '4px',
    left: '0px',
    top: '0px',
    margin: '100px auto 100px',
    maxWidth: '960px',
    minWidth: '600px',
    outline: '0px',
    padding: '0px',
    position: 'relative',
    minHeight: '300px',
    maxHeight: 'auto',
    overflow: 'visible',
  },
  overlay: {
    backgroundColor: 'rgba(159, 170, 181, 0.90)',
    overflow: 'auto',
    paddingBottom: '300px',
  },
};

interface IState {
  packageName: string;
  packageDescription: string;
}

interface IPrivateProps {
  isModalOpen: boolean;
  mode: ModalEnum;
  packagePayload?: IPackage;
  bot?: IBot;
  pluginType?: string;
  selectedResources: string[];
  descriptor?: IDetailedDescriptor;
  resource?: string;
  data?: {};
  name?: string;
  description?: string;
  message?: string;
  onConfirm?(): void;
  addPlugin?(plugins: string[]): void;
}

class ModalComponentFrame extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      packageName: '',
      packageDescription: '',
    };
  }

  setPackageName = (name: string) => {
    this.setState({ packageName: name });
  };

  setPackageDescription = (description: string) => {
    this.setState({ packageDescription: description });
  };

  renderContent(mode: ModalEnum) {
    switch (mode) {
      case ModalEnum.createBot:
        return <CreateBotModal />;
      case ModalEnum.editBot:
        return <EditBotModal bot={this.props.bot} />;
      case ModalEnum.createPackage:
        // todo check this functionality, setMode has been removed
        return (
          <CreatePackageModal
            setName={this.setPackageName}
            setDescription={this.setPackageDescription}
          />
        );
      case ModalEnum.editPackage:
        return <EditPackageModal packagePayload={this.props.packagePayload} />;
      case ModalEnum.viewJson:
        return <ViewJsonModal resource={this.props.resource} />;
      case ModalEnum.updatePackage:
        return (
          <UpdatePackageModal
            packageName={this.state.packageName}
            packageDescription={this.state.packageDescription}
          />
        );
      case ModalEnum.addPackages:
        return <AddPackagesModal bot={this.props.bot} />;
      case ModalEnum.editJson:
        return (
          <EditJsonModal
            resource={this.props.resource}
            data={this.props.data}
          />
        );
      case ModalEnum.addPlugins:
        return (
          <AddPluginModal
            oldPlugins={this.props.selectedResources}
            pluginType={this.props.pluginType}
            addPlugins={this.props.addPlugin}
          />
        );
      case ModalEnum.createNewConfig:
        return (
          <CreateNewConfigModal
            type={this.props.pluginType}
            name={this.props.name}
            description={this.props.description}
            data={this.props.data}
          />
        );
      case ModalEnum.createNewConfig2:
        return (
          <CreateNewConfig2Modal
            type={this.props.pluginType}
            name={this.props.name}
            description={this.props.description}
            data={this.props.data}
          />
        );
      case ModalEnum.updatePackages:
        return <UpdatePackagesModal pluginResource={this.props.resource} />;
      case ModalEnum.updateBots:
        return (
          <UpdateBotsModal packageResources={this.props.selectedResources} />
        );
      case ModalEnum.confirmation:
        return (
          <ConfirmModal
            message={this.props.message}
            onConfirm={this.props.onConfirm}
          />
        );
      default:
        return null;
    }
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  render() {
    return (
      <Modal
        isOpen={this.props.isModalOpen}
        style={customStyles}
        ariaHideApp={false}
        onRequestClose={this.closeModal}
        shouldCloseOnOverlayClick={false}
        bodyOpenClassName={'modal-body-open'}>
        <span onClick={this.closeModal} style={styles.close} className="close">
          &times;
        </span>
        {this.renderContent(this.props.mode)}
      </Modal>
    );
  }
}

const ComposedModalComponentFrame: Component<IPrivateProps> = compose<
  IPrivateProps
>(pure, setDisplayName('Modal'), connect(modalSelector))(ModalComponentFrame);

export default ComposedModalComponentFrame;
