import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import BlueButton from '../Assets/Buttons/BlueButton';
import { IDescriptor } from '../utils/AxiosFunctions';
import useStyles from './ModalComponent.styles';
import './ModalComponent.styles.scss';

interface IProps {
  descriptor: IDescriptor;
}

const EditDescriptorModal = ({ descriptor }: IProps) => {
  const [name, setName] = React.useState(descriptor.name || '');
  const [description, setDescription] = React.useState(
    descriptor.description || '',
  );

  const classes = useStyles();

  return (
    <div>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          <div className={classes.botHeaderText}>
            {'Edit name and description'}
          </div>
          <BlueButton
            text={'Save'}
            classes={{ button: classes.button }}
            disabled={
              name === descriptor.name && description === descriptor.description
            }
            onClick={() => {
              eddiApiActionDispatchers.updateDescriptorAction(
                descriptor.resource,
                name,
                description,
              );
              ModalActionDispatchers.closeModal();
            }}
          />
        </div>
      </div>
      <div className={classes.content}>
        <div className={classes.botText}>
          {'Name'}
          <div className={classes.inputBoxContent}>
            <textarea
              defaultValue={name}
              name={'name'}
              className={classes.inputBoxName}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
        </div>
        <div className={classes.botText}>
          {'Description'}
          <div className={classes.inputBoxContent}>
            <textarea
              defaultValue={description}
              name={'description'}
              className={classes.inputBox}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
const ComposedEditDescriptorModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('EditDescriptorModal'),
)(EditDescriptorModal);

export default ComposedEditDescriptorModal;
