import clsx from 'clsx';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../../styles/DefaultStylingProperties';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import Parser from '../../utils/Parser';
import useStyles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';

interface IProps {
  name: string;
  description: string;
  type: string;
  data: string;
  onConfirm(): void;
}

const CreateNewConfigModal = (props: IProps) => {
  const [name, setName] = React.useState(props.name || '');
  const [description, setDescription] = React.useState(props.description || '');
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

  const nextButton = () => {
    ModalActionDispatchers.showCreateNewConfig2Modal(
      props.type,
      name,
      description,
      props.data,
      props.onConfirm,
    );
  };

  const typeName = Parser.getPluginName(props.type, false);
  return (
    <div>
      <div className={classes.tallModalHeader}>
        <div className={classes.modalTopHeader}>
          <h2 className={classes.createPackageHeaderText}>
            {`Create new ${typeName}`}
          </h2>
          <div className={classes.modalTopHeaderCenter} />
          <button
            disabled={!name}
            onClick={() => {
              nextButton();
            }}
            style={getButtonStyle()}
            className={clsx(
              classes.createNewBotButton,
              classes.createNewConfigModalButton,
            )}>
            {`Next`}
          </button>
        </div>
      </div>
      <div className={classes.content}>
        <div className={classes.botText}>
          {`Give the ${typeName} a name`}
          <div className={classes.inputBoxContent}>
            <textarea
              defaultValue={name}
              name={'name'}
              className={classes.inputBoxName}
              placeholder={`Give the ${typeName} a name..`}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
        </div>
        <div className={classes.botText}>
          {`Give the ${typeName} a short description`}
          <div className={classes.inputBoxContent}>
            <textarea
              defaultValue={description}
              name={'description'}
              className={classes.inputBox}
              placeholder={`Give the ${typeName} a short description..`}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

const ComposedCreateNewConfigModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('CreateNewConfigModal'),
)(CreateNewConfigModal);

export default ComposedCreateNewConfigModal;
