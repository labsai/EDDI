import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import authenticationActionDispatchers from '../../../actions/AuthenticationActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { authenticationSelector } from '../../../selectors/AuthenticationSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { getAPIUrl } from '../../utils/ApiFunctions';
import '../ModalComponent.styles.scss';
import useStyles from './BasicAuthModal.styles';

const warningIcon = require('../../../../public/images/WarningIcon@3x.png');

interface IPrivateProps extends IPublicProps {
  isAuthenticated: boolean;
  error: Error;
}

interface IPublicProps {}

const BasicAuthModal = (props: IPrivateProps) => {
  const classes = useStyles();
  const [name, setName] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [apiUrl, setApiUrl] = React.useState('');

  const asyncSetApiUrl = async () => {
    const apiUrl = await getAPIUrl();
    setApiUrl(apiUrl);
  };

  React.useEffect(() => {
    asyncSetApiUrl();
  }, []);

  const signIn = (event) => {
    event.preventDefault();
    authenticationActionDispatchers.basicAuthSignInAction(name, password);
  };

  const handleButtonPress = (e) => {
    if (e.key === 'Enter') {
      signIn(e);
    }
  };

  return (
    <form>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          {`${apiUrl} is requesting authentication`}
        </div>
      </div>
      <div className={classes.content}>
        <div className={classes.message}>{'Sign in'}</div>
        {!!props.error && (
          <div className={classes.error}>
            <img src={warningIcon} className={classes.warningIcon} />
            <div className={classes.errorMessage}>
              {'Invalid username or password.'}
            </div>
          </div>
        )}
        <div className={classes.inputTitle}>{'Username'}</div>
        <input
          className={classes.input}
          type={'text'}
          autoComplete={'username'}
          autoFocus={true}
          onChange={(e) => setName(e.target.value)}
        />
        <div className={classes.inputTitle}>{'Password'}</div>
        <input
          type={'password'}
          autoComplete={'current-password'}
          className={classes.input}
          onChange={(e) => setPassword(e.target.value)}
          onKeyPress={handleButtonPress}
        />
        <div className={classes.buttons}>
          <BlueButton
            classes={{ button: classes.buttonMargin }}
            text={'Sign in'}
            onClick={signIn}
          />
          <WhiteButton
            text={'Cancel'}
            onClick={modalActionDispatchers.closeModal}
          />
        </div>
      </div>
    </form>
  );
};

const ComposedBasicAuthModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(authenticationSelector),
  setDisplayName('BasicAuthModal'),
)(BasicAuthModal);

export default ComposedBasicAuthModal;
