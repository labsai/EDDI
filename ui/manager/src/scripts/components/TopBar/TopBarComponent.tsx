import { makeStyles } from '@material-ui/core/styles';
import WarningRoundedIcon from '@material-ui/icons/WarningRounded';
import * as Keycloak from 'keycloak-js';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import {
  MEDIUM_FONT,
  MEDIUM_FONT2,
  RED_BORDER,
  RED_COLOR,
} from '../../../styles/DefaultStylingProperties';
import authenticationActionDispatchers from '../../actions/AuthenticationActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import {
  authenticationSelector,
  readOnlySelector,
} from '../../selectors/AuthenticationSelectors';
import BlueButton from '../Assets/Buttons/BlueButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { pageEnum } from '../pages/pageEnum';
import { ModalEnum } from '../utils/ModalEnum';
import FilterComponent from './FilterComponent';
import NavigationComponent from './NavigationComponent';

const useStyles = makeStyles({
  createNewBotButton: {
    height: '36px',
    marginLeft: '32px',
    marginTop: '7px',
    minWidth: '108px',

    '&:disabled': {
      color: '#fff',
    },
  },
  topBarCenter: {
    flex: 1,
  },
  topBarComponent: {
    display: 'flex',
    height: '50px',
    marginBottom: '15px',
    width: '100%',
  },
  readOnly: {
    border: RED_BORDER,
    color: RED_COLOR,
    fontSize: MEDIUM_FONT,
    padding: '5px',
    margin: 'auto',
    display: 'table',
    borderRadius: '5px',
  },
  warning: {
    display: 'flex',
  },
  warningIcon: {
    height: '38px',
    width: '38px',
    marginRight: '5px',
    marginTop: 'auto',
    marginBottom: 'auto',
  },
  logoutButton: {
    height: '36px',
    float: 'right',
  },
  warningText: {
    whiteSpace: 'pre',
  },
  bold: {
    fontSize: MEDIUM_FONT2,
    fontWeight: 'bold',
  },
  link: {
    cursor: 'pointer',
  },
});

interface IPublicProps {
  page: pageEnum;
  type?: string;
  filter(text: string): void;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
  keycloak: Keycloak.KeycloakInstance;
}

const TopBarComponent = ({
  page,
  type,
  filter,
  readOnly,
  keycloak,
}: IPrivateProps) => {
  const classes = useStyles();
  const openModal = () => {
    switch (page) {
      case pageEnum.bot:
        ModalActionDispatchers.showModal(ModalEnum.createBot);
        return;
      case pageEnum.package:
        ModalActionDispatchers.showModal(ModalEnum.createPackage);
        return;
      default:
        ModalActionDispatchers.showCreateNewConfigModal(type);
        return;
    }
  };

  const getSearchName = (page: pageEnum) => {
    if (page === pageEnum.httpCalls) {
      return 'HTTP calls';
    } else if (page === pageEnum.gitCalls) {
      return 'Git calls';
    } else {
      return pageEnum[page];
    }
  };

  const logout = () => {
    historyPush('/');
    authenticationActionDispatchers.signOutAction(keycloak);
  };

  return (
    <div>
      {readOnly && (
        <div className={classes.readOnly}>
          <div className={classes.warning}>
            <WarningRoundedIcon className={classes.warningIcon} />
            <div className={classes.warningText}>
              <span className={classes.bold}>
                {'This is a read-only demo Instance of EDDI.'}
              </span>
              {
                '\nCreate your own bot today by launching EDDI with one-click on Google Marketplace. '
              }
              <a
                className={classes.link}
                onClick={() =>
                  window.open(
                    'https://console.cloud.google.com/marketplace/details/labsai-public/labsai-eddi-dev',
                    '_blank',
                  )
                }>
                {'Learn more.'}
              </a>
            </div>
          </div>
        </div>
      )}
      {!!keycloak && (
        <WhiteButton
          text={'Logout'}
          classes={{ button: classes.logoutButton }}
          onClick={logout}
        />
      )}
      <div className={classes.topBarComponent}>
        <NavigationComponent page={page} />
        <div className={classes.topBarCenter} />
        <FilterComponent page={page} filter={filter} />
        {page !== pageEnum.conversation && (
          <BlueButton
            text={`Create new ${getSearchName(page)}`}
            classes={{ button: classes.createNewBotButton }}
            disabled={readOnly}
            onClick={openModal}
          />
        )}
      </div>
    </div>
  );
};

const ComposedTopBarComponent: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  connect(authenticationSelector),
  setDisplayName('TopBarComponent'),
)(TopBarComponent);

export default ComposedTopBarComponent;
