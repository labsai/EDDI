import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { DARK_GREY_COLOR } from '../../../styles/DefaultStylingProperties';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { modalSelector } from '../../selectors/ModalSelectors';
import { getTypeFromResource } from '../utils/ApiFunctions';
import { IBot, IDetailedDescriptor, IPackage } from '../utils/AxiosFunctions';
import { ModalEnum } from '../utils/ModalEnum';
import AddPackagesModal from './AddPackagesModal/AddPackagesModal';
import AddPluginModal from './AddPluginModal/AddPluginModal';
import BasicAuthModal from './BasicAuthModal/BasicAuthModal';
import BotLogsModal from './BotLogsModal/BotLogsModal';
import ConfirmModal from './ConfirmModal';
import ConversationsModal from './ConversationsModal/ConversationsModal';
import CreateBotModal from './CreateBotModal';
import CreatePackageModal from './CreatePackageModal';
import EditDescriptorModal from './EditDescriptorModal';
import CreateNewConfig2Modal from './EditJsonModal/CreateNewConfig2Modal';
import CreateNewConfigModal from './EditJsonModal/CreateNewConfigModal';
import EditJsonModal from './EditJsonModal/EditJsonModal';
import ErrorMessageModal from './ErrorMessageModal';
import './ModalComponent.styles.scss';
import ParallelConfigModal from './ParallelConfigModal/ParallelConfigModal';
import AddNewPackageToBotModal from './UpdateConfigsModal/AddNewPackageToBotModal';
import UpdateBotsModal from './UpdateConfigsModal/UpdateBotsModal';
import UpdatePackagesModal from './UpdateConfigsModal/UpdatePackagesModal';
import UpdatePackageModal from './UpdatePackageModal';
import ViewJsonModal from './ViewJsonModal/ViewJsonModal';

const useStyles = makeStyles({
  content: {
    background: '#fff',
    boxShadow: `0 0 20px ${DARK_GREY_COLOR}`,
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
    '&:focus': {
      color: '#000',
      cursor: 'pointer',
    },
    '&:hover': {
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
});

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
  data?: string;
  name?: string;
  description?: string;
  message?: string;
  title?: string;
  onConfirm?(): void;
  addPlugin?(plugins: string[]): void;
}

interface IPublicProps {}

const ModalComponentFrame = (props: IPrivateProps) => {
  const [packageName, setPackageName] = React.useState('');
  const [packageDescription, setPackageDescription] = React.useState('');

  const classes = useStyles();

  const handleSetPackageName = (name: string) => {
    setPackageName(name);
  };

  const handleSetPackageDescription = (description: string) => {
    setPackageDescription(description);
  };

  const renderContent = (mode: ModalEnum) => {
    switch (mode) {
      case ModalEnum.createBot:
        return <CreateBotModal />;
      case ModalEnum.editDescriptor:
        return <EditDescriptorModal descriptor={props.descriptor} />;
      case ModalEnum.createPackage:
        return (
          <CreatePackageModal
            setName={handleSetPackageName}
            setDescription={handleSetPackageDescription}
          />
        );
      case ModalEnum.viewJson:
        return <ViewJsonModal resource={props.resource} />;
      case ModalEnum.updatePackage:
        return (
          <UpdatePackageModal
            packageName={packageName}
            packageDescription={packageDescription}
          />
        );
      case ModalEnum.addPackages:
        return <AddPackagesModal bot={props.bot} />;
      case ModalEnum.editJson:
        const type = getTypeFromResource(props.resource);
        return (
          <EditJsonModal
            type={type}
            resource={props.resource}
            data={props.data}
          />
        );
      case ModalEnum.addPlugins:
        return (
          <AddPluginModal
            oldPlugins={props.selectedResources}
            pluginType={props.pluginType}
            addPlugins={props.addPlugin}
          />
        );
      case ModalEnum.createNewConfig:
        return (
          <CreateNewConfigModal
            type={props.pluginType}
            name={props.name}
            description={props.description}
            data={props.data}
            onConfirm={props.onConfirm}
          />
        );
      case ModalEnum.createNewConfig2:
        return (
          <CreateNewConfig2Modal
            type={props.pluginType}
            name={props.name}
            description={props.description}
            data={props.data}
            onConfirm={props.onConfirm}
          />
        );
      case ModalEnum.updatePackages:
        return <UpdatePackagesModal pluginResource={props.resource} />;
      case ModalEnum.updateBots:
        return <UpdateBotsModal packageResources={props.selectedResources} />;
      case ModalEnum.confirmation:
        return (
          <ConfirmModal
            title={props.title}
            message={props.message}
            onConfirm={props.onConfirm}
          />
        );
      case ModalEnum.error:
        return (
          <ErrorMessageModal title={props.title} message={props.message} />
        );
      case ModalEnum.addNewPackageToBot:
        return (
          <AddNewPackageToBotModal packagePayload={props.packagePayload} />
        );
      case ModalEnum.conversations:
        return <ConversationsModal bot={props.bot} />;
      case ModalEnum.basicAuth:
        return <BasicAuthModal />;
      case ModalEnum.showBotLogs:
        return <BotLogsModal bot={props.bot} />;
      case ModalEnum.parallelConfig:
        return <ParallelConfigModal packagePayload={props.packagePayload} />;
      default:
        return null;
    }
  };

  const closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  if (props.isModalOpen) {
    document.body.className = 'modal-body-open';
    return (
      <div className={classes.overlay}>
        <div className={classes.box}>
          <div onClick={closeModal} className={classes.close}>
            &times;
          </div>
          <div className={classes.content}>{renderContent(props.mode)}</div>
        </div>
      </div>
    );
  }
  document.body.className = 'modal-body-closed';
  return <div />;
};

const ComposedModalComponentFrame: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('Modal'),
  connect(modalSelector),
)(ModalComponentFrame);

export default ComposedModalComponentFrame;
