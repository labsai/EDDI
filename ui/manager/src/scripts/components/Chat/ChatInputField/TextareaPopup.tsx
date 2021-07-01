import Button from '@material-ui/core/Button';
import Popover from '@material-ui/core/Popover';
import { makeStyles } from '@material-ui/core/styles';
import TextareaAutosize from '@material-ui/core/TextareaAutosize';
import * as React from 'react';
import { useDispatch, useSelector } from 'react-redux';
import {
  BLUE_COLOR,
  DARK_GREY_COLOR,
  RED_COLOR,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import { setChatContext } from '../../../actions/ChatActions';
import { getChatContext } from '../../../selectors/ChatSelectors';
import CONTEXT_EXAMPLE from '../enums/contextExample';

const useStyles = makeStyles({
  popup: {
    padding: '10px',
    display: 'flex',
    flexDirection: 'column',
  },
  actionButtons: {
    marginTop: '10px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',

    '& button': {
      borderRadius: '15px',
      margin: '0 5px',
      boxSizing: 'border-box',
      transition: 'border 0s ease',

      '&:hover': {
        padding: '2px 8px',
        transition: 'border 0s ease',
      },
    },
  },
  textarea: {
    minWidth: '247px',
    backgroundColor: DARK_GREY_COLOR,
    color: WHITE_COLOR,
    transition: 'border 0s ease',

    '&:focus': {
      border: `2px solid ${BLUE_COLOR}`,
      borderRadius: '3px',
      transition: 'border 0s ease',
    },
    '&:focus-visible': {
      outline: 'none',
    },
  },
  setButton: {
    backgroundColor: BLUE_COLOR,
    color: WHITE_COLOR,

    '&:hover': {
      border: `2px solid ${BLUE_COLOR}`,
      backgroundColor: 'transparent',
      color: BLUE_COLOR,
    },
  },
  closeButton: {
    backgroundColor: RED_COLOR,
    color: WHITE_COLOR,

    '&:hover': {
      border: `2px solid ${RED_COLOR}`,
      backgroundColor: 'transparent',
      color: RED_COLOR,
    },
  },
  insertButton: {
    backgroundColor: YELLOW_COLOR,
    color: WHITE_COLOR,

    '&:hover': {
      border: `2px solid ${YELLOW_COLOR}`,
      backgroundColor: 'transparent',
      color: YELLOW_COLOR,
    },
  },
});

interface ITextareaPopup {
  popupEl: null | HTMLElement;
  open: boolean;
  handleClose: () => void;
}

const TextareaPopup = ({ open, popupEl, handleClose }: ITextareaPopup) => {
  const context = useSelector(getChatContext);
  const [value, setValue] = React.useState<string>(context || '');
  const classes = useStyles();
  const dispatch = useDispatch();

  const handleSetContext = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    dispatch(setChatContext(value));
    handleClose();
  };

  const handleClosePopup = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    handleClose();
  };

  const handleChangeContext = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setValue(e.target.value);
  };

  const handleInsertExample = (e: React.MouseEvent<HTMLButtonElement>) => {
    setValue(CONTEXT_EXAMPLE);
  };

  return (
    <Popover
      id="textarea-popup"
      open={open}
      anchorEl={popupEl}
      onClose={handleClosePopup}
      anchorOrigin={{
        vertical: 'bottom',
        horizontal: 'center',
      }}
      transformOrigin={{
        vertical: 'top',
        horizontal: 'center',
      }}>
      <div className={classes.popup}>
        <TextareaAutosize
          className={classes.textarea}
          rows={4}
          autoFocus
          value={value}
          onChange={handleChangeContext}></TextareaAutosize>
        <div className={classes.actionButtons}>
          <Button
            size="small"
            variant="contained"
            onClick={handleSetContext}
            className={classes.setButton}
            color="primary">
            {'Set'}
          </Button>
          <Button
            size="small"
            variant="contained"
            onClick={handleClosePopup}
            className={classes.closeButton}
            color="secondary">
            {'Close'}
          </Button>
          <Button
            size="small"
            variant="contained"
            className={classes.insertButton}
            onClick={handleInsertExample}>
            {'Insert Example'}
          </Button>
        </div>
      </div>
    </Popover>
  );
};

export default TextareaPopup;
