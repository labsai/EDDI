import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import Button from './Button';
import * as Radium from 'radium';
import {
  ERROR,
  IN_PROGRESS,
  NOT_FOUND,
  READY,
} from '../../utils/helpers/BotHelper';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { getDeploymentStatus } from '../../utils/AxiosFunctions';
import {
  DARK_GREEN_COLOR,
  DARK_RED_COLOR,
  GREEN_BORDER,
  GREEN_COLOR,
  LIGHT_GREY_BORDER,
  RED_BORDER,
  RED_COLOR,
  YELLOW_BORDER,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const undeployStyle: CSSProperties = {
  button: {
    color: 'white',
    backgroundColor: RED_COLOR,
    border: RED_BORDER,
    ':hover': {
      backgroundColor: DARK_RED_COLOR,
    },
  },
};
const deployStyle: CSSProperties = {
  button: {
    color: 'white',
    backgroundColor: GREEN_COLOR,
    border: GREEN_BORDER,
    ':hover': {
      backgroundColor: DARK_GREEN_COLOR,
    },
  },
};
const inProgressStyle: CSSProperties = {
  disabled: {
    color: 'white',
    backgroundColor: YELLOW_COLOR,
    border: YELLOW_BORDER,
    cursor: 'default',
  },
};
const errorStyle: CSSProperties = {
  disabled: {
    color: DARK_RED_COLOR,
    backgroundColor: 'white',
    border: LIGHT_GREY_BORDER,
    cursor: 'default',
  },
};

interface IProps {
  botName: string;
  botResource: string;
  deploymentStatus: string;
  customStyles: {};
  readOnly: boolean;
}

const sleep = milliseconds => {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
};

class DeployButton extends React.Component<IProps> {
  componentDidMount() {
    this.waitForUpdatedStatus();
  }

  componentWillReceiveProps(nextProps) {
    this.waitForUpdatedStatus();
  }

  checkStatus() {
    return getDeploymentStatus(this.props.botResource);
  }

  async waitForUpdatedStatus() {
    const status = await this.checkStatus();
    if (status === IN_PROGRESS) {
      return await sleep(500).then(() => this.waitForUpdatedStatus());
    } else {
      eddiApiActionDispatchers.fetchBotDeploymentStatusAction(
        this.props.botResource,
      );
      return status;
    }
  }

  getButton() {
    switch (this.props.deploymentStatus) {
      case READY:
        return (
          <Button
            text={'Undeploy'}
            onClick={() =>
              modalActionDispatchers.showConfirmationModal(
                `Are you sure you want to undeploy ${this.props.botName}?`,
                null,
                () =>
                  eddiApiActionDispatchers.undeployBotAction(
                    this.props.botResource,
                  ),
              )
            }
            styles={undeployStyle}
            customStyles={this.props.customStyles}
            disabled={this.props.readOnly}
          />
        );
      case ERROR:
        return (
          <Button
            text={'ERROR'}
            styles={errorStyle}
            customStyles={this.props.customStyles}
            disabled={true}
          />
        );
      case IN_PROGRESS:
        return (
          <Button
            text={'In Progress'}
            styles={inProgressStyle}
            customStyles={this.props.customStyles}
            disabled={true}
          />
        );
      case NOT_FOUND:
        return (
          <Button
            text={'Deploy'}
            onClick={() =>
              eddiApiActionDispatchers.deployBotAction(this.props.botResource)
            }
            styles={deployStyle}
            customStyles={this.props.customStyles}
            disabled={this.props.readOnly}
          />
        );
      case undefined:
        return (
          <Button
            text={'Loading...'}
            styles={errorStyle}
            customStyles={this.props.customStyles}
            disabled={true}
          />
        );
      default:
        return (
          <Button
            text={'STATUS ERROR'}
            styles={errorStyle}
            customStyles={this.props.customStyles}
            disabled={true}
          />
        );
    }
  }

  render() {
    return this.getButton();
  }
}

const ComposedDeployButton: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('DeployButton'),
)(DeployButton);

export default ComposedDeployButton;
