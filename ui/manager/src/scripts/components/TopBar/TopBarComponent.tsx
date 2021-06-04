import * as Keycloak from 'keycloak-js';
import Radium from 'radium';
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

const styles: { [key: string]: IExtendedCSSProperties } = {
  createNewBotButton: {
    height: '36px',
    marginLeft: '32px',
    marginTop: '7px',
    minWidth: '108px',
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
};

const warningIcon = require('../../../public/images/WarningIcon@3x.png');

interface IPublicProps {
  page: pageEnum;
  type?: string;
  filter(text: string): void;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
  keycloak: Keycloak.KeycloakInstance;
}

class TopBarComponent extends React.Component<IPrivateProps> {
  openModal = () => {
    switch (this.props.page) {
      case pageEnum.bot:
        ModalActionDispatchers.showModal(ModalEnum.createBot);
        return;
      case pageEnum.package:
        ModalActionDispatchers.showModal(ModalEnum.createPackage);
        return;
      default:
        ModalActionDispatchers.showCreateNewConfigModal(this.props.type);
        return;
    }
  };

  getSearchName(page: pageEnum) {
    if (page === pageEnum.httpCalls) {
      return 'HTTP calls';
    } else if (page === pageEnum.gitCalls) {
      return 'Git calls';
    } else {
      return pageEnum[page];
    }
  }

  logout = () => {
    historyPush('/');
    authenticationActionDispatchers.signOutAction(this.props.keycloak);
  };

  render() {
    return (
      <div>
        {this.props.readOnly && (
          <div style={styles.readOnly}>
            <div style={styles.warning}>
              <img src={warningIcon} style={styles.warningIcon} />
              <div style={styles.warningText}>
                <span style={styles.bold}>
                  {'This is a read-only demo Instance of EDDI.'}
                </span>
                {
                  '\nCreate your own bot today by launching EDDI with one-click on Google Marketplace. '
                }
                <a
                  style={styles.link}
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
        {!!this.props.keycloak && (
          <WhiteButton
            text={'Logout'}
            customStyles={styles.logoutButton}
            onClick={this.logout}
          />
        )}
        <div style={styles.topBarComponent}>
          <NavigationComponent page={this.props.page} />
          <div style={styles.topBarCenter} />
          <FilterComponent page={this.props.page} filter={this.props.filter} />
          {this.props.page !== pageEnum.conversation && (
            <BlueButton
              text={`Create new ${this.getSearchName(this.props.page)}`}
              customStyles={styles.createNewBotButton}
              disabled={this.props.readOnly}
              onClick={this.openModal}
            />
          )}
        </div>
      </div>
    );
  }
}

const ComposedTopBarComponent: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  connect(authenticationSelector),
  Radium,
  setDisplayName('TopBarComponent'),
)(TopBarComponent);

export default ComposedTopBarComponent;
