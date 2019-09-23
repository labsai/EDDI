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
import EditDescriptorModal from './EditDescriptorModal';
import AddPackagesModal from './AddPackagesModal/AddPackagesModal';
import AddPluginModal from './AddPluginModal/AddPluginModal';
import CreatePackageModal from './CreatePackageModal';
import UpdatePackageModal from './UpdatePackageModal';
import { ModalEnum } from '../utils/ModalEnum';
import EditJsonModal from './EditJsonModal/EditJsonModal';
import ViewJsonModal from './ViewJsonModal/ViewJsonModal';
import CreateNewConfigModal from './EditJsonModal/CreateNewConfigModal';
import CreateNewConfig2Modal from './EditJsonModal/CreateNewConfig2Modal';
import UpdatePackagesModal from './UpdateConfigsModal/UpdatePackagesModal';
import UpdateBotsModal from './UpdateConfigsModal/UpdateBotsModal';
import ConfirmModal from './ConfirmModal';
import ErrorMessageModal from './ErrorMessageModal';
import { CSSProperties } from 'react';
import AddNewPackageToBotModal from './UpdateConfigsModal/AddNewPackageToBotModal';
import { getTypeFromResource } from '../utils/ApiFunctions';
import ConversationsModal from './ConversationsModal/ConversationsModal';
import BasicAuthModal from './BasicAuthModal/BasicAuthModal';

const randomStyles: CSSProperties = {
  content: {
    background: '#fff',
    border: '1px solid #ccc',
    borderRadius: '4px',
    left: '0px',
    top: '0px',
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
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  close: {
    ':focus': {
      color: '#000',
      cursor: 'pointer',
    },
    ':hover': {
      color: '#000',
      cursor: 'pointer',
    },
    marginLeft: 'auto',
    color: '#FFF',
    cursor: 'pointer',
    fontSize: '40px',
    position: 'relative',
    width: '22px',
    height: '22px',
    lineHeight: '20px',
  },
  box: {
    maxWidth: '960px',
    minWidth: '600px',
    position: 'relative',
    margin: '100px auto 100px',
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
  title?: string;
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
      case ModalEnum.editDescriptor:
        return <EditDescriptorModal descriptor={this.props.descriptor} />;
      case ModalEnum.createPackage:
        return (
          <CreatePackageModal
            setName={this.setPackageName}
            setDescription={this.setPackageDescription}
          />
        );
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
        const type = getTypeFromResource(this.props.resource);
        return (
          <EditJsonModal
            type={type}
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
            onConfirm={this.props.onConfirm}
          />
        );
      case ModalEnum.createNewConfig2:
        return (
          <CreateNewConfig2Modal
            type={this.props.pluginType}
            name={this.props.name}
            description={this.props.description}
            data={this.props.data}
            onConfirm={this.props.onConfirm}
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
            title={this.props.title}
            message={this.props.message}
            onConfirm={this.props.onConfirm}
          />
        );
      case ModalEnum.error:
        return (
          <ErrorMessageModal
            title={this.props.title}
            message={this.props.message}
          />
        );
      case ModalEnum.addNewPackageToBot:
        return (
          <AddNewPackageToBotModal packagePayload={this.props.packagePayload} />
        );
      case ModalEnum.conversations:
        return <ConversationsModal bot={this.props.bot} />;
      case ModalEnum.basicAuth:
        return <BasicAuthModal />;
      default:
        return null;
    }
  }

  closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  render() {
    if (this.props.isModalOpen) {
      document.body.className = 'modal-body-open';
      return (
        <div style={randomStyles.overlay}>
          <div style={randomStyles.box}>
            <div onClick={this.closeModal} style={randomStyles.close}>
              &times;
            </div>
            <div style={randomStyles.content}>
              {this.renderContent(this.props.mode)}
            </div>
          </div>
        </div>
      );
    }
    document.body.className = 'modal-body-closed';
    return <div />;
  }
}

const ComposedModalComponentFrame: Component<IPrivateProps> = compose<
  IPrivateProps
>(pure, setDisplayName('Modal'), connect(modalSelector))(ModalComponentFrame);

export default ComposedModalComponentFrame;
