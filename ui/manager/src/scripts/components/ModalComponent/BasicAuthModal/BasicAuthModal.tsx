import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './BasicAuthModal.styles';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import BlueButton from '../../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as renderIf from 'render-if';
import { connect } from 'react-redux';
import { authenticationSelector } from '../../../selectors/AuthenticationSelectors';
import authenticationActionDispatchers from '../../../actions/AuthenticationActionDispatchers';
import * as _ from 'lodash';

const warningIcon = require('../../../../public/images/WarningIcon@3x.png');

interface IPrivateProps extends IPublicProps {
  isAuthenticated: boolean;
  error: Error;
}

interface IPublicProps {}

interface IState {
  name: string;
  password: string;
}
class BasicAuthModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      name: '',
      password: '',
    };
  }

  signIn = () => {
    authenticationActionDispatchers.basicAuthSignInAction(
      this.state.name,
      this.state.password,
    );
  };

  render() {
    return (
      <div>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            {'EDDI is requesting authentication'}
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.message}>{'Sign in'}</div>
          {renderIf(this.props.error)(() => (
            <div style={styles.error}>
              <img src={warningIcon} style={styles.warningIcon} />
              <div style={styles.errorMessage}>
                {'Invalid username or password.'}
              </div>
            </div>
          ))}
          <div style={styles.inputTitle}>{'Username'}</div>
          <input
            style={styles.input}
            onChange={e =>
              this.setState({
                name: e.target.value,
              })
            }
          />
          <div style={styles.inputTitle}>{'Password'}</div>
          <input
            type={'password'}
            style={styles.input}
            onChange={e =>
              this.setState({
                password: e.target.value,
              })
            }
          />
          <div style={styles.buttons}>
            <BlueButton
              customStyles={styles.buttonMargin}
              text={'Sign in'}
              onClick={this.signIn}
            />
            <WhiteButton
              text={'Cancel'}
              onClick={modalActionDispatchers.closeModal}
            />
          </div>
        </div>
      </div>
    );
  }
}

const ComposedBasicAuthModal: Component<IProps> = compose<IProps>(
  pure,
  connect(authenticationSelector),
  setDisplayName('BasicAuthModal'),
)(BasicAuthModal);

export default ComposedBasicAuthModal;
