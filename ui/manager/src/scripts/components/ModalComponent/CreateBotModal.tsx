import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { createNewBot } from '../utils/AxiosFunctions';
import useStyles from './ModalComponent.styles';
import './ModalComponent.styles.scss';

const CreateBotModal = () => {
  const [name, setName] = React.useState('');
  const [description, setDescription] = React.useState('');

  const classes = useStyles();

  const getButtonStyle = () => {
    if (!name) {
      return { backgroundColor: '#c4c9d2' };
    } else {
      return {
        backgroundColor: BLUE_COLOR,
        cursor: 'pointer',
      };
    }
  };

  const createBot = async () => {
    const botID = await createNewBot(name, description);
    eddiApiActionDispatchers.createNewBotAction(botID);
    historyPush(`/botview/${botID}`);
    modalActionDispatchers.closeModal();
  };

  return (
    <div>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          <h2 className={classes.botHeaderText}>{'Create new bot'}</h2>
          <button
            disabled={!name}
            style={getButtonStyle()}
            className={classes.createNewBotButton}
            onClick={() => createBot()}>
            {'Create bot'}
          </button>
        </div>
      </div>
      <div className={classes.content}>
        <div className={classes.botText}>
          {'Give the bot a name'}
          <div className={classes.inputBoxContent}>
            <textarea
              name={'name'}
              defaultValue={''}
              className={classes.inputBoxName}
              placeholder={'Give the bot a name..'}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
        </div>
        <div className={classes.botText}>
          {'Give the bot a short description'}
          <div className={classes.inputBoxContent}>
            <textarea
              name={'description'}
              className={classes.inputBox}
              defaultValue={''}
              placeholder={'Give the bot a short description..'}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

const ComposedCreateBotModal = compose(
  pure,
  setDisplayName('Modal'),
)(CreateBotModal);

export default ComposedCreateBotModal;
