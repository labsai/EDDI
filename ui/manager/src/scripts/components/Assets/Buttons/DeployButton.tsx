import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
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
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { getDeploymentStatus } from '../../utils/AxiosFunctions';
import {
  ERROR,
  IN_PROGRESS,
  NOT_FOUND,
  READY,
} from '../../utils/helpers/BotHelper';
import Button from './Button';
import { ClassNameMap } from '@material-ui/styles/withStyles';
import clsx from 'clsx';

const useStyles = makeStyles({
  undeployStyle: {
    color: 'white',
    backgroundColor: RED_COLOR,
    border: RED_BORDER,

    '&:hover': {
      backgroundColor: DARK_RED_COLOR,
    },
    '&:disabled': {
      cursor: 'not-allowed',
    },
  },
  deployStyle: {
    color: 'white',
    backgroundColor: GREEN_COLOR,
    border: GREEN_BORDER,
    '&:hover': {
      backgroundColor: DARK_GREEN_COLOR,
    },
    '&:disabled': {
      cursor: 'not-allowed',
    },
  },
  inProgressStyle: {
    '&:disabled': {
      color: 'white',
      backgroundColor: YELLOW_COLOR,
      border: YELLOW_BORDER,
      cursor: 'not-allowed',
    },
  },
  errorStyle: {
    '&:disabled': {
      color: DARK_RED_COLOR,
      backgroundColor: 'white',
      border: LIGHT_GREY_BORDER,
      cursor: 'not-allowed',
    },
  },
});

interface IProps {
  botName: string;
  botResource: string;
  deploymentStatus: string;
  customStyles?: {};
  readOnly: boolean;
  classes?: ClassNameMap;
}

const sleep = (milliseconds) => {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
};

const DeployButton = ({
  botName,
  botResource,
  deploymentStatus,
  customStyles,
  readOnly,
  classes: externalClasses,
}: IProps) => {
  const classes = useStyles();
  const waitForUpdatedStatus = async () => {
    const status = await checkStatus();
    if (status === IN_PROGRESS) {
      return await sleep(500).then(() => waitForUpdatedStatus());
    } else {
      eddiApiActionDispatchers.fetchBotDeploymentStatusAction(botResource);
      return status;
    }
  };

  React.useEffect(() => {
    waitForUpdatedStatus();
  }, [botName, botResource, deploymentStatus]);

  const checkStatus = () => {
    return getDeploymentStatus(botResource);
  };

  const getButton = () => {
    switch (deploymentStatus) {
      case READY:
        return (
          <Button
            text={'Undeploy'}
            onClick={() =>
              modalActionDispatchers.showConfirmationModal(
                `Are you sure you want to undeploy ${botName}?`,
                null,
                () => eddiApiActionDispatchers.undeployBotAction(botResource),
              )
            }
            classes={{
              button: clsx(classes.undeployStyle, externalClasses?.button),
            }}
            customStyles={customStyles}
            disabled={readOnly}
          />
        );
      case ERROR:
        return (
          <Button
            text={'ERROR'}
            classes={{
              button: clsx(classes.errorStyle, externalClasses?.button),
            }}
            customStyles={customStyles}
            disabled={true}
          />
        );
      case IN_PROGRESS:
        return (
          <Button
            text={'In Progress'}
            classes={{
              button: clsx(classes.inProgressStyle, externalClasses?.button),
            }}
            customStyles={customStyles}
            disabled={true}
          />
        );
      case NOT_FOUND:
        return (
          <Button
            text={'Deploy'}
            onClick={() =>
              eddiApiActionDispatchers.deployBotAction(botResource)
            }
            classes={{
              button: clsx(classes.deployStyle, externalClasses?.button),
            }}
            customStyles={customStyles}
            disabled={readOnly}
          />
        );
      case undefined:
        return (
          <Button
            text={'Loading...'}
            classes={{
              button: clsx(classes.errorStyle, externalClasses?.button),
            }}
            customStyles={customStyles}
            disabled={true}
          />
        );
      default:
        return (
          <Button
            text={'STATUS ERROR'}
            classes={{
              button: clsx(classes.errorStyle, externalClasses?.button),
            }}
            customStyles={customStyles}
            disabled={true}
          />
        );
    }
  };

  return getButton();
};

const ComposedDeployButton: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('DeployButton'),
)(DeployButton);

export default ComposedDeployButton;
