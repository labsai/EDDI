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

const styles: CSSProperties = {
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

const undeployStyle: CSSProperties = {
  button: {
    color: 'white',
    backgroundColor: '#FF5976',
    border: '1px solid #FF5976',
    ':hover': {
      backgroundColor: 'red',
    },
  },
};
const deployStyle: CSSProperties = {
  button: {
    color: 'white',
    backgroundColor: '#4BCA81',
    border: '1px solid #4BCA81',
    ':hover': {
      backgroundColor: 'green',
    },
  },
};
const inProgressStyle: CSSProperties = {
  disabled: {
    color: 'white',
    backgroundColor: '#FADA5E',
    border: '1px solid #FADA5E',
    cursor: 'default',
  },
};
const errorStyle: CSSProperties = {
  disabled: {
    color: 'red',
    backgroundColor: 'white',
    border: '1px solid #D8DDE6',
    cursor: 'default',
  },
};

interface IProps {
  botName: string;
  botResource: string;
  deploymentStatus: string;
  customStyles: {};
}

class DeployButton extends React.Component<IProps> {
  getButton() {
    switch (this.props.deploymentStatus) {
      case READY:
        return (
          <Button
            text={'Undeploy'}
            onClick={() =>
              modalActionDispatchers.showConfirmationModal(
                `Are you sure you want to undeploy ${this.props.botName}?`,
                () =>
                  eddiApiActionDispatchers.undeployBotAction(
                    this.props.botResource,
                  ),
              )
            }
            styles={{ ...styles, ...undeployStyle }}
            customStyles={this.props.customStyles}
          />
        );
      case ERROR:
        return (
          <Button
            text={'ERROR'}
            styles={{ ...styles, ...errorStyle }}
            customStyles={this.props.customStyles}
            disabled={true}
          />
        );
      case IN_PROGRESS:
        return (
          <Button
            text={'In Progress'}
            styles={{ ...styles, ...inProgressStyle }}
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
            styles={{ ...styles, ...deployStyle }}
            customStyles={this.props.customStyles}
          />
        );
      default:
        return (
          <Button
            text={'STATUS ERROR'}
            styles={{ ...styles, ...errorStyle }}
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
