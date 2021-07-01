import IconButton from '@material-ui/core/IconButton';
import ListItemIcon from '@material-ui/core/ListItemIcon';
import Menu from '@material-ui/core/Menu';
import MenuItem from '@material-ui/core/MenuItem';
import { makeStyles } from '@material-ui/core/styles';
import Typography from '@material-ui/core/Typography';
import AddIcon from '@material-ui/icons/Add';
import BurstModeIcon from '@material-ui/icons/BurstMode';
import InfoIcon from '@material-ui/icons/Info';
import LaunchIcon from '@material-ui/icons/Launch';
import MoreVertIcon from '@material-ui/icons/MoreVert';
import * as React from 'react';
import { useSelector } from 'react-redux';
import {
  BLUE_COLOR,
  GREEN_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import {
  getChatAnimation,
  getChatContext,
} from '../../../selectors/ChatSelectors';
import InfoPopup from './InfoPopup';
import TextareaPopup from './TextareaPopup';

const ITEM_HEIGHT = 48;

const useStyles = makeStyles({
  menu: {
    '& .MuiListItemIcon-root': {
      minWidth: 20,
    },
  },
  submenu: {
    justifyContent: 'space-between',

    '& .MuiListItemIcon-root': {
      justifyContent: 'flex-end',
    },
  },
  optionButton: {
    alignContent: 'center',
    marginLeft: '5px',
    position: 'relative',

    '& .MuiButtonBase-root': {
      padding: '3px',
    },

    '&:hover svg': {
      color: BLUE_COLOR,
    },

    '& svg': {
      color: WHITE_COLOR,
    },
  },
  contextIndicator: {
    width: '7px',
    height: '7px',
    borderRadius: '3px',
    backgroundColor: GREEN_COLOR,
    position: 'absolute',
    top: 0,
    right: 0,
  },
  absoluteContainer: {
    position: 'absolute',
    left: '5px',
    top: '10px',
  },
});

const ChatOptions = ({
  botResource,
  startNewConversation,
  setChatAnimation,
}: {
  botResource: string;
  startNewConversation: () => void;
  setChatAnimation: (state: boolean) => void;
  restartChat: () => void;
}) => {
  const context = useSelector(getChatContext);
  const animation: boolean = useSelector(getChatAnimation);

  const classes = useStyles();
  const [anchorEl, setAnchorEl] = React.useState<null | HTMLElement>(null);
  const [popupEl, setPopupEl] = React.useState<null | HTMLElement>(null);
  const [infoPopupEl, setInfoPopupEl] =
    React.useState<null | HTMLElement>(null);

  const open = Boolean(anchorEl);
  const openedPopup = Boolean(popupEl);
  const openedInfoPopup = Boolean(infoPopupEl);

  // show dropdown list
  const handleClick = (event: React.MouseEvent<HTMLElement>) => {
    event.stopPropagation();
    setAnchorEl(event.currentTarget);
  };

  const handleClose = () => {
    setAnchorEl(null);
  };

  const handleClosePopup = () => {
    setPopupEl(null);
  };

  const handleCloseInfoPopup = () => {
    setInfoPopupEl(null);
  };

  // open textarea for custom context (debugging bot dirrectly from chat)
  const handleOpenPopup = (event: React.MouseEvent<HTMLElement>) => {
    event.stopPropagation();
    setPopupEl(event.currentTarget);
  };

  // open bit info popup
  const handleOpenInfoPopup = (event: React.MouseEvent<HTMLElement>) => {
    event.stopPropagation();
    setInfoPopupEl(event.currentTarget);
  };

  const animationText = `${animation ? 'Disable' : 'Enable'} delay animation`;

  return (
    <div className={classes.absoluteContainer}>
      <div className={classes.optionButton} id="chat-options">
        <IconButton
          aria-label="more"
          aria-controls="long-menu"
          aria-haspopup="true"
          onClick={handleClick}>
          <MoreVertIcon fontSize="large" />
        </IconButton>
        <Menu
          id="long-menu"
          anchorEl={anchorEl}
          keepMounted
          open={open}
          className={classes.menu}
          onClose={handleClose}
          PaperProps={{
            style: {
              maxHeight: ITEM_HEIGHT * 4.5,
              width: '20ch',
            },
          }}>
          <MenuItem
            key={'Add context'}
            aria-describedby="textarea-popup"
            onClick={(e) => {
              handleClose();
              handleOpenPopup(e);
            }}>
            <ListItemIcon>
              <AddIcon fontSize="small" />
            </ListItemIcon>
            <Typography variant="inherit">{'Add context'}</Typography>
            <TextareaPopup
              open={openedPopup}
              popupEl={popupEl}
              handleClose={handleClosePopup}
            />
          </MenuItem>
          <MenuItem
            key={'Info'}
            aria-describedby="info-popup"
            onClick={(e) => {
              handleClose();
              handleOpenInfoPopup(e);
            }}>
            <ListItemIcon>
              <InfoIcon fontSize="small" />
            </ListItemIcon>
            <Typography variant="inherit">{'Info'}</Typography>
            <InfoPopup
              open={openedInfoPopup}
              popupEl={infoPopupEl}
              handleClose={handleCloseInfoPopup}
              botResource={botResource}
            />
          </MenuItem>
          <MenuItem
            key={'New conversation'}
            onClick={(e) => {
              handleClose();
              startNewConversation();
            }}>
            <ListItemIcon>
              <LaunchIcon fontSize="small" />
            </ListItemIcon>
            <Typography variant="inherit">{'New conversation'}</Typography>
          </MenuItem>
          <MenuItem
            key={animationText}
            onClick={() => {
              handleClose();
              setChatAnimation(!animation);
            }}>
            <ListItemIcon>
              <BurstModeIcon fontSize="small" />
            </ListItemIcon>
            <Typography variant="inherit">{animationText}</Typography>
          </MenuItem>
        </Menu>
        {!!context && <div className={classes.contextIndicator} />}
      </div>
    </div>
  );
};

export default ChatOptions;
