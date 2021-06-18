import Popover from '@material-ui/core/Popover';
import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import TextField from '@material-ui/core/TextField';
import Button from '@material-ui/core/Button';
import * as _ from 'lodash';
import { getChatContext } from '../../../selectors/ChatSelectors';
import { useDispatch, useSelector } from 'react-redux';
import { setChatContext } from '../../../actions/ChatActions';

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

  const handleChangeContext = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
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
        <TextField
          className={classes.textarea}
          variant="outlined"
          multiline
          rows={4}
          value={value}
          onChange={handleChangeContext}></TextField>
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
        </div>
      </div>
    </Popover>
  );
};

export default TextareaPopup;
