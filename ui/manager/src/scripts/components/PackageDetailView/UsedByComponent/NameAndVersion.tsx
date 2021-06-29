import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import clsx from 'clsx';
import {
  WHITE_COLOR,
  GREY_COLOR,
  BLUE_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  content: {
    display: 'inline-flex',
    marginRight: '15px',
    marginTop: '5px',
    paddingTop: '5px',
    paddingBottom: '5px',
    cursor: 'pointer',
  },
  name: {
    color: WHITE_COLOR,
    fontSize: '16px',

    '&:hover': {
      color: BLUE_COLOR,
    },
  },
  smallName: {
    color: GREY_COLOR,
    marginTop: '3px',
    fontSize: '13px',
  },
  version: {
    color: GREY_COLOR,
    fontSize: '12px',
    marginTop: '4px',
    marginLeft: '5px',
  },
});

interface IProps {
  descriptor: IDetailedDescriptor;
  usedByOlderVersion: boolean;
  isSmallName: boolean;
  onClick(): void;
}

const NameAndVersion = ({
  descriptor,
  usedByOlderVersion,
  isSmallName,
  onClick,
}: IProps) => {
  const classes = useStyles();

  const buttonClick = () => {
    onClick();
    modalActionDispatchers.closeModal();
  };

  return (
    <div className={classes.content} onClick={buttonClick}>
      <div
        className={clsx({
          [classes.smallName]: isSmallName,
          [classes.name]: !isSmallName,
        })}>
        {descriptor.name || descriptor.id}
      </div>
      <div className={classes.version}>{`v${descriptor.version}${
        !!usedByOlderVersion ? '*' : ''
      }`}</div>
    </div>
  );
};

const ComposedNameAndVersion: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('NameAndVersion'),
)(NameAndVersion);

export default ComposedNameAndVersion;
