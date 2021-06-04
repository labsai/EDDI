import * as React from 'react';
import styles from './BasicAuthModal.styles';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import BlueButton from '../../Assets/Buttons/BlueButton';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { connect } from 'react-redux';
import { authenticationSelector } from '../../../selectors/AuthenticationSelectors';
import authenticationActionDispatchers from '../../../actions/AuthenticationActionDispatchers';
import * as _ from 'lodash';
import { getAPIUrl } from '../../utils/ApiFunctions';

const warningIcon = require('../../../../public/images/WarningIcon@3x.png');

interface IPrivateProps extends IPublicProps {
  isAuthenticated: boolean;
  error: Error;
}

interface IPublicProps {}

interface IState {
  apiUrl: string;
  name: string;
  password: string;
}
class BasicAuthModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      apiUrl: '',
      name: '',
      password: '',
    };
  }

  async componentDidMount() {
    const apiUrl = await getAPIUrl();
    this.setState({ apiUrl });
  }

  signIn = (event) => {
    event.preventDefault();
    authenticationActionDispatchers.basicAuthSignInAction(
      this.state.name,
      this.state.password,
    );
  };

  handleButtonPress = (e) => {
    if (e.key === 'Enter') {
      this.signIn(e);
    }
  };

  render() {
    return (
      <form>
        <div style={styles.modalHeader}>
          <div style={styles.modalTopHeader}>
            {`${this.state.apiUrl} is requesting authentication`}
          </div>
        </div>
        <div style={styles.content}>
          <div style={styles.message}>{'Sign in'}</div>
          {!!this.props.error && (
            <div style={styles.error}>
              <img src={warningIcon} style={styles.warningIcon} />
              <div style={styles.errorMessage}>
                {'Invalid username or password.'}
              </div>
            </div>
          )}
          <div style={styles.inputTitle}>{'Username'}</div>
          <input
            style={styles.input}
            type={'text'}
            autoComplete={'username'}
            autoFocus={true}
            onChange={(e) =>
              this.setState({
                name: e.target.value,
              })
            }
          />
          <div style={styles.inputTitle}>{'Password'}</div>
          <input
            type={'password'}
            autoComplete={'current-password'}
            style={styles.input}
            onChange={(e) =>
              this.setState({
                password: e.target.value,
              })
            }
            onKeyPress={this.handleButtonPress}
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
      </form>
    );
  }
}

const ComposedBasicAuthModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(authenticationSelector),
  setDisplayName('BasicAuthModal'),
)(BasicAuthModal);

export default ComposedBasicAuthModal;
