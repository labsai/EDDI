import Popover from '@material-ui/core/Popover';
import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import Button from '@material-ui/core/Button';
import * as _ from 'lodash';
import { getChatContext } from '../../../selectors/ChatSelectors';
import { useDispatch, useSelector } from 'react-redux';
import { setChatContext } from '../../../actions/ChatActions';
import TextareaAutosize from '@material-ui/core/TextareaAutosize';
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
      borderRadius: '10px',
      margin: '0 5px',
    },
  },
  textarea: {
    minWidth: '200px',
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
            color="primary">
            {'Set'}
          </Button>
          <Button
            size="small"
            variant="contained"
            onClick={handleClosePopup}
            color="secondary">
            {'Close'}
          </Button>
          <Button
            size="small"
            variant="contained"
            onClick={handleInsertExample}>
            {'Insert Example'}
          </Button>
        </div>
      </div>
    </Popover>
  );
};

export default TextareaPopup;
